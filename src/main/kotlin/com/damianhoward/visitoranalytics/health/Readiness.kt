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
    private val process: ProcessMetrics = ProcessMetrics(),
    private val clock: Clock = Clock.systemUTC(),
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    /**
     * One evaluation of everything above, which both `/readyz` and `/metrics` render, so the two
     * cannot disagree rather than merely being expected not to (document 17).
     *
     * It matters more here than the rule alone suggests. The tailer's liveness fields are written
     * by the poll loop as it runs, so reading them twice can straddle a poll: `/metrics` could
     * publish an age from before a poll alongside offsets from after it, and describe a service
     * state that never existed.
     */
    private data class Snapshot(
        val ready: Boolean,
        val databaseOk: Boolean,
        val ingestOk: Boolean,
        val threadAlive: Boolean,
        val pollAgeMillis: Long?,
        val failure: String?,
        val offsets: Map<String, Long>,
    )

    private fun snapshot(): Snapshot {
        val database = databaseOk()
        val alive = ingestAlive()
        val pollAge = ingestPollAgeMillis(clock.millis())
        // A null age means no poll has completed yet, which is startup rather than failure — the
        // thread being alive is all that can be known that early.
        val polling = pollAge == null || pollAge <= maxPollAge.toMillis()
        val ingestOk = alive && polling
        return Snapshot(
            ready = database && ingestOk,
            databaseOk = database,
            ingestOk = ingestOk,
            threadAlive = alive,
            pollAgeMillis = pollAge,
            failure = ingestFailure(),
            offsets = ingestOffsets(),
        )
    }

    fun probe(): Probe {
        val now = snapshot()
        val offsets =
            now.offsets
                .entries
                .sortedBy { it.key }
                .joinToString(",") { (path, offset) -> "${quote(path)}:$offset" }

        val json =
            """{"ready":${now.ready},"database":{"ok":${now.databaseOk}},""" +
                """"ingest":{"ok":${now.ingestOk},"threadAlive":${now.threadAlive},""" +
                """"pollAgeMillis":${now.pollAgeMillis ?: "null"},""" +
                """"lastFailure":${now.failure?.let(::quote) ?: "null"},"offsets":{$offsets}}}"""
        return Probe(now.ready, json)
    }

    /**
     * The same snapshot in Prometheus text format.
     *
     * The ingest verdict gets its conditions alongside it, unlike `orderbook` where the verdict is
     * the only condition. `ingest_up` false with `thread_alive` true is a loop that has stalled
     * mid-poll; false with `thread_alive` false is a thread that died. Both are 503 and they need
     * different things done about them, so a single series would lose the distinction the JSON body
     * already draws.
     *
     * The last failure appears as a boolean, not its message. A poll failure's text is written by
     * whatever threw — a path, a permission, a filesystem error — so it is exactly the free text
     * document 17 keeps out of an endpoint, and as a label it would mint a new series per distinct
     * message. Whether polls are currently failing is the part that trends; the message is in the
     * log and in `/readyz`, both of which are read by someone who already has the host.
     *
     * The offset label is a file path, which is data-shaped but bounded by configuration: the
     * tailer keys its positions by the paths it was told to watch, so the set is fixed at startup
     * and does not grow with traffic or rotation.
     */
    fun metrics(): String {
        val now = snapshot()
        val out = StringBuilder()

        fun emit(
            name: String,
            help: String,
            type: String,
            samples: List<Pair<String, Any>>,
        ) {
            if (samples.isEmpty()) return
            out
                .append("# HELP ")
                .append(name)
                .append(' ')
                .append(help)
                .append('\n')
            out
                .append("# TYPE ")
                .append(name)
                .append(' ')
                .append(type)
                .append('\n')
            for ((labels, value) in samples) {
                out
                    .append(name)
                    .append(labels)
                    .append(' ')
                    .append(value)
                    .append('\n')
            }
        }

        fun gauge(
            name: String,
            help: String,
            value: Any,
        ) = emit(name, help, "gauge", listOf("" to value))

        gauge("visitor_analytics_ready", "Whether every readiness condition below currently holds.", now.ready.toInt())
        gauge("visitor_analytics_database_up", "Whether the database answered its check.", now.databaseOk.toInt())
        gauge(
            "visitor_analytics_ingest_up",
            "Whether the tail loop is alive and has polled within its budget.",
            now.ingestOk.toInt(),
        )
        gauge(
            "visitor_analytics_ingest_thread_alive",
            "Whether the tail thread exists. False with ingest_up false means it died rather than stalled.",
            now.threadAlive.toInt(),
        )
        now.pollAgeMillis?.let {
            gauge(
                "visitor_analytics_ingest_poll_age_seconds",
                "Seconds since the tail loop last completed a poll. Absent before its first poll.",
                seconds(it),
            )
        }
        gauge(
            "visitor_analytics_ingest_poll_failing",
            "Whether the most recent poll failed. The loop stays alive and retries from the same offset.",
            (now.failure != null).toInt(),
        )
        emit(
            "visitor_analytics_ingest_offset_bytes",
            "Bytes consumed from each tailed file. Unmoved while the site serves is the alert.",
            "gauge",
            now.offsets.entries
                .sortedBy { it.key }
                .map { """{file=${quote(it.key)}}""" to it.value },
        )

        // Appended, not interleaved: these are process facts rather than readiness conditions, and
        // keeping them in their own block is what stops one being mistaken for the other.
        out.append(process.render())

        return out.toString()
    }

    private fun Boolean.toInt(): Int = if (this) 1 else 0

    // Rendered rather than divided into a Double, so a duration never reaches the endpoint in
    // exponential notation — Prometheus accepts it, humans reading a curl do not.
    private fun seconds(millis: Long): String = "%d.%03d".format(millis / 1000, millis % 1000)

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
