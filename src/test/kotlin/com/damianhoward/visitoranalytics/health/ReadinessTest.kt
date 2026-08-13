package com.damianhoward.visitoranalytics.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ReadinessTest {
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun readiness(
        databaseOk: Boolean = true,
        alive: Boolean = true,
        pollAgeMillis: Long? = 500,
        failure: String? = null,
        offsets: Map<String, Long> = mapOf("/var/log/caddy/orderbook.log" to 4096),
    ) = Readiness(
        databaseOk = { databaseOk },
        ingestAlive = { alive },
        ingestPollAgeMillis = { pollAgeMillis },
        ingestFailure = { failure },
        ingestOffsets = { offsets },
        clock = clock,
    )

    @Test
    fun `a working service is ready and reports both halves`() {
        val probe = readiness().probe()
        assertTrue(probe.ready)
        assertTrue(probe.json.contains(""""database":{"ok":true}"""))
        assertTrue(probe.json.contains(""""threadAlive":true"""))
        assertTrue(probe.json.contains(""""pollAgeMillis":500"""))
    }

    @Test
    fun `a dead tailer is not ready even though the database answers`() {
        // The failure this whole class exists for: the dashboard keeps serving from the database
        // while nothing is being recorded. Before this, the probe reported only the database and
        // stayed green.
        val probe = readiness(alive = false).probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""ingest":{"ok":false"""))
        assertTrue(probe.json.contains(""""database":{"ok":true}"""), "the database half is still true, and says so")
    }

    @Test
    fun `a thread that is alive but has stopped polling is not ready`() {
        // Alive is not enough. A thread blocked on a hung filesystem call reports alive forever.
        val probe = readiness(pollAgeMillis = Duration.ofSeconds(31).toMillis()).probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""pollAgeMillis":31000"""))
    }

    @Test
    fun `a poll age inside the window is ready`() {
        assertTrue(readiness(pollAgeMillis = Duration.ofSeconds(29).toMillis()).probe().ready)
    }

    @Test
    fun `no completed poll yet is startup, not failure`() {
        // Between the thread starting and its first cycle finishing there is no age to judge.
        val probe = readiness(pollAgeMillis = null).probe()
        assertTrue(probe.ready)
        assertTrue(probe.json.contains(""""pollAgeMillis":null"""))
    }

    @Test
    fun `a quiet site is ready — liveness is the loop, not the traffic`() {
        // Offsets unmoved for hours is a site nobody visited, not a broken tailer. Keying the
        // probe on ingest volume would page on a quiet night and stay silent on a dead thread.
        val probe = readiness(offsets = mapOf("/var/log/caddy/orderbook.log" to 0)).probe()
        assertTrue(probe.ready)
    }

    @Test
    fun `a repeatedly failing poll is reported but does not fail the probe`() {
        // The loop is alive, the offset is not advanced, and the next poll retries the same place.
        // Nothing is lost and a restart would not help, so this is reported rather than paged on.
        val probe = readiness(failure = "permission denied").probe()
        assertTrue(probe.ready)
        assertTrue(probe.json.contains(""""lastFailure":"permission denied""""))
    }

    @Test
    fun `a database that stops answering is not ready`() {
        val probe = readiness(databaseOk = false).probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""database":{"ok":false}"""))
    }

    @Test
    fun `offsets are reported per path, sorted, with paths quoted`() {
        val probe =
            readiness(
                offsets = mapOf("/var/log/remote/trading.log" to 900, "/var/log/caddy/orderbook.log" to 100),
            ).probe()
        assertEquals(
            true,
            probe.json.contains(""""offsets":{"/var/log/caddy/orderbook.log":100,"/var/log/remote/trading.log":900}"""),
            probe.json,
        )
    }

    @Test
    fun `metrics renders the same verdict as the probe`() {
        val healthy = readiness()
        assertTrue(healthy.probe().ready)
        assertTrue(healthy.metrics().contains("visitor_analytics_ready 1"), healthy.metrics())

        val stopped = readiness(alive = false)
        assertFalse(stopped.probe().ready)
        assertTrue(stopped.metrics().contains("visitor_analytics_ready 0"), stopped.metrics())
    }

    @Test
    fun `a stalled loop and a dead thread are distinguishable in the metrics`() {
        // Both are 503 and they need different things done about them, so the verdict alone is not
        // enough: a stalled loop may recover on its next poll, a dead thread never will.
        val stalled = readiness(pollAgeMillis = Duration.ofMinutes(5).toMillis()).metrics()
        assertTrue(stalled.contains("visitor_analytics_ingest_up 0"), stalled)
        assertTrue(stalled.contains("visitor_analytics_ingest_thread_alive 1"), stalled)

        val dead = readiness(alive = false).metrics()
        assertTrue(dead.contains("visitor_analytics_ingest_up 0"), dead)
        assertTrue(dead.contains("visitor_analytics_ingest_thread_alive 0"), dead)
    }

    @Test
    fun `poll age is published in seconds`() {
        // Prometheus base units. Every alert expression and dashboard function assumes them, and a
        // threshold written against milliseconds is off by a factor of a thousand in the direction
        // that never fires.
        val metrics = readiness(pollAgeMillis = 1500).metrics()
        assertTrue(metrics.contains("visitor_analytics_ingest_poll_age_seconds 1.500"), metrics)
    }

    @Test
    fun `poll age is absent before the first poll rather than published as zero`() {
        // Zero would read as a poll that just completed, which is the opposite of what a service
        // that has never polled should say.
        val metrics = readiness(pollAgeMillis = null).metrics()
        assertFalse(metrics.contains("visitor_analytics_ingest_poll_age_seconds"), metrics)
    }

    @Test
    fun `a poll failure is published as a boolean, never as its message`() {
        // The message is written by whatever threw — a path, a permission, a filesystem error — so
        // it is the free text document 17 keeps out of an endpoint, and as a label it would mint a
        // series per distinct message. /readyz still carries it for a reader who has the host.
        val metrics = readiness(failure = "permission denied: /var/log/caddy/orderbook.log").metrics()
        assertTrue(metrics.contains("visitor_analytics_ingest_poll_failing 1"), metrics)
        assertFalse(metrics.contains("permission denied"), metrics)
    }

    @Test
    fun `offsets are labelled by file and sorted`() {
        val metrics =
            readiness(
                offsets = mapOf("/var/log/remote/trading.log" to 900, "/var/log/caddy/orderbook.log" to 100),
            ).metrics()
        val lines = metrics.lineSequence().filter { it.startsWith("visitor_analytics_ingest_offset_bytes") }.toList()
        assertEquals(
            listOf(
                """visitor_analytics_ingest_offset_bytes{file="/var/log/caddy/orderbook.log"} 100""",
                """visitor_analytics_ingest_offset_bytes{file="/var/log/remote/trading.log"} 900""",
            ),
            lines,
            metrics,
        )
    }

    @Test
    fun `metrics carry heap against its ceiling, which is what sizes the ceiling`() {
        // This service is the largest process on box 1 — 142 MB resident against a 96 MB heap
        // ceiling — and was the last of the five with no heap series at all, so its ceiling could
        // only ever be sized from a reading taken by hand.
        val metrics = readiness().metrics()

        assertTrue(metrics.contains("visitor_analytics_jvm_heap_used_bytes"), metrics)
        assertTrue(metrics.contains("visitor_analytics_jvm_heap_max_bytes"), metrics)
        assertTrue(metrics.contains("visitor_analytics_process_uptime_seconds"), metrics)
    }

    @Test
    fun `process metrics do not restate a readiness condition`() {
        // The one-snapshot rule holds because this service renders readiness per scrape. A process
        // gauge duplicating a readiness one would be a second number free to drift from the first.
        val metrics = readiness().metrics()
        val declared = metrics.lines().filter { it.startsWith("# TYPE ") }.map { it.split(' ')[2] }

        assertEquals(declared.size, declared.distinct().size, "a series is declared twice: $declared")
    }

    @Test
    fun `every published series carries its HELP and TYPE`() {
        // A series without a TYPE is parsed as untyped, which silently costs rate() and increase().
        val body = readiness().metrics()
        val names =
            body
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringBefore(' ').substringBefore('{') }
                .distinct()
                .toList()
        assertTrue(names.isNotEmpty(), body)
        for (name in names) {
            assertTrue(body.contains("# HELP $name "), "$name has no HELP\n$body")
            assertTrue(body.contains("# TYPE $name "), "$name has no TYPE\n$body")
        }
    }
}
