package io.github.damian1000.visitoranalytics.health

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
}
