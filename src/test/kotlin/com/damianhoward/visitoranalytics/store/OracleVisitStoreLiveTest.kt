package com.damianhoward.visitoranalytics.store

import com.damianhoward.visitoranalytics.sampleVisit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.time.Instant

/**
 * One real hit against the Autonomous DB — H2's Oracle mode can't prove the actual dialect,
 * driver, or wallet handshake. Needs the wallet and credentials, so it runs where they exist
 * (the box, or a dev machine with `VISITOR_DB_URL`/`VISITOR_DB_USER`/`VISITOR_DB_PASSWORD`
 * exported), and is skipped elsewhere — CI has no wallet secret by design.
 *
 * The smoke visit is dated 1970 so the cleanup delete (`< epoch+1h`) can never touch real
 * rows, which are all current.
 */
@EnabledIfEnvironmentVariable(named = "VISITOR_DB_URL", matches = ".+")
class OracleVisitStoreLiveTest {
    @Test
    fun `records, reads back, and deletes against the real database`() {
        val store =
            OracleVisitStore({
                DriverManager.getConnection(
                    System.getenv("VISITOR_DB_URL"),
                    System.getenv("VISITOR_DB_USER"),
                    System.getenv("VISITOR_DB_PASSWORD"),
                )
            })

        val smoke = sampleVisit(path = "/live-smoke", at = Instant.EPOCH)
        store.record(smoke)

        val readBack = store.recent(500).filter { it.path == "/live-smoke" }
        assertThat(readBack.size, greaterThanOrEqualTo(1))
        assertThat(readBack.first().geo, equalTo(smoke.geo))

        assertThat(store.deleteOlderThan(Instant.EPOCH.plusSeconds(3600)), greaterThanOrEqualTo(1))
    }
}
