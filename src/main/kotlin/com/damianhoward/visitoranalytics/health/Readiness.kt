package com.damianhoward.visitoranalytics.health

import java.time.Clock
import java.time.Duration

/**
 * The service's operational truth, aggregated for `/readyz`: the database answering, and the
 * ingest loop still turning over.
 *
 * `/healthz` proves the admin server answers. This proves the service is doing its job, and the
 * two are further apart here than anywhere else on the estate — the dashboard reads from the
 * database and never touches the tailer, so a dead ingest thread leaves every page serving
 * correctly while nothing is recorded. That failure is invisible from outside until someone
 * notices the visit count stopped moving, which is exactly the shape of "up and wrong" that
 * document 17 exists for. The tailer's own KDoc named it long before anything reported it.
 *
 * Ingest liveness is the poll loop's age, not the arrival of lines. A site with no visitors
 * produces no lines for hours and is entirely healthy; the loop still polls on its interval. A
 * probe keyed on volume would page on a quiet night and stay silent on a dead tailer, which is
 * the wrong answer in both directions.
 *
 * A poll that fails every time is reported but does not by itself fail the probe: the loop is
 * alive, the offset is not advanced, and the next poll retries from the same place. What fails
 * the probe is the loop stopping — [maxPollAge] past its interval — because nothing recovers from
 * that without a restart.
 */
class Readiness(
    private val databaseOk: () -> Boolean,
    private val ingestAlive: () -> Boolean,
    private val ingestPollAgeMillis: (Long) -> Long?,
    private val ingestFailure: () -> String?,
    private val ingestOffsets: () -> Map<String, Long>,
    private val maxPollAge: Duration = MAX_POLL_AGE,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    fun probe(): Probe {
        val database = databaseOk()
        val alive = ingestAlive()
        val pollAge = ingestPollAgeMillis(clock.millis())
        // A null age means no poll has completed yet, which is startup rather than failure — the
        // thread being alive is all that can be known that early.
        val polling = pollAge == null || pollAge <= maxPollAge.toMillis()
        val ingestOk = alive && polling
        val ready = database && ingestOk

        val failure = ingestFailure()
        val offsets =
            ingestOffsets()
                .entries
                .sortedBy { it.key }
                .joinToString(",") { (path, offset) -> "${quote(path)}:$offset" }

        val json =
            """{"ready":$ready,"database":{"ok":$database},""" +
                """"ingest":{"ok":$ingestOk,"threadAlive":$alive,"pollAgeMillis":${pollAge ?: "null"},""" +
                """"lastFailure":${failure?.let(::quote) ?: "null"},"offsets":{$offsets}}}"""
        return Probe(ready, json)
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /**
         * Thirty seconds against a one-second poll interval. Generous on purpose: the threshold
         * answers "has this stopped", and a loop that is thirty polls late has stopped by any
         * useful definition. Matching trading-system's figure keeps one number to remember.
         */
        private val MAX_POLL_AGE: Duration = Duration.ofSeconds(30)
    }
}
