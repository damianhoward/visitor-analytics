package io.github.damian1000.visitoranalytics.retention

import io.github.damian1000.visitoranalytics.store.VisitStore
import java.time.Clock
import java.time.Duration

/**
 * Deletes visits older than the retention window — the stored data is coarse and hashed, but
 * keeping it forever would still outgrow the stated 90-day promise. Scheduled daily by the
 * composition root; safe to run any time.
 */
class RetentionPruner(
    private val store: VisitStore,
    private val retentionDays: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun prune(): Int {
        val pruned = store.deleteOlderThan(clock.instant().minus(Duration.ofDays(retentionDays.toLong())))
        if (pruned > 0) println("visitor-analytics: pruned $pruned visit(s) past $retentionDays-day retention")
        return pruned
    }
}
