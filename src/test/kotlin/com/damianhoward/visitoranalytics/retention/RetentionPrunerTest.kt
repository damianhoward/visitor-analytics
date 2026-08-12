package com.damianhoward.visitoranalytics.retention

import com.damianhoward.visitoranalytics.FakeVisitStore
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RetentionPrunerTest {
    @Test
    fun `prunes at exactly now minus retention`() {
        val now = Instant.parse("2026-07-11T10:00:00Z")
        var cutoffSeen: Instant? = null
        val store =
            object : FakeVisitStore() {
                override fun deleteOlderThan(cutoff: Instant): Int {
                    cutoffSeen = cutoff
                    return 3
                }
            }

        val pruned = RetentionPruner(store, retentionDays = 90, clock = Clock.fixed(now, ZoneOffset.UTC)).prune()

        assertThat(pruned, equalTo(3))
        assertThat(cutoffSeen, equalTo(Instant.parse("2026-04-12T10:00:00Z")))
    }
}
