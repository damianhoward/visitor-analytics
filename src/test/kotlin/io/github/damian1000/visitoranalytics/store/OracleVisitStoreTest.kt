package io.github.damian1000.visitoranalytics.store

import io.github.damian1000.visitoranalytics.sampleVisit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The store's SQL against H2 in Oracle compatibility mode — the same `V1__visits.sql` DDL the
 * real database gets, executed statement by statement. `OracleVisitStoreLiveTest` covers the
 * dialect drift H2 can't.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleVisitStoreTest {
    private val url = "jdbc:h2:mem:visits;MODE=Oracle;DB_CLOSE_DELAY=-1"
    private val connect: () -> Connection = { DriverManager.getConnection(url) }
    private val now = Instant.parse("2026-07-11T12:00:00Z")
    private val store = OracleVisitStore(connect, Clock.fixed(now, ZoneOffset.UTC))

    @BeforeAll
    fun createSchema() {
        // The same migration files the real database gets, in order. Comment lines go first:
        // they may contain semicolons, and the split below is naive.
        for (migration in listOf("V1__visits.sql", "V2__org_domain.sql")) {
            val ddl =
                javaClass
                    .getResource("/db/migration/$migration")!!
                    .readText()
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
            connect().use { connection ->
                for (statement in ddl.split(";").map(String::trim).filter(String::isNotEmpty)) {
                    connection.createStatement().use { it.execute(statement) }
                }
            }
        }
    }

    @BeforeEach
    fun clear() {
        connect().use { it.createStatement().use { s -> s.execute("DELETE FROM visits") } }
    }

    @Test
    fun `a recorded visit reads back whole`() {
        val visit = sampleVisit(engaged = true)
        store.record(visit)
        assertThat(store.recent(10), contains(visit))
    }

    @Test
    fun `null geo fields survive the roundtrip`() {
        val visit = sampleVisit(country = null, city = null, asn = null, org = null, referrer = null)
        store.record(visit)
        assertThat(store.recent(10), contains(visit))
    }

    @Test
    fun `recent is newest first and bounded`() {
        for (hour in 1..5) store.record(sampleVisit(path = "/h$hour", at = now.minusSeconds(3600L * hour)))
        val paths = store.recent(3).map { it.path }
        assertThat(paths, equalTo(listOf("/h1", "/h2", "/h3")))
    }

    @Test
    fun `visits per day groups and windows`() {
        store.record(sampleVisit(at = now.minusSeconds(3600), engaged = true))
        store.record(sampleVisit(at = now.minusSeconds(7200)))
        store.record(sampleVisit(at = now.minusSeconds(86_400 * 2)))
        store.record(sampleVisit(at = now.minusSeconds(86_400L * 40))) // outside the window

        val days = store.visitsPerDay(days = 30)
        assertThat(days.size, equalTo(2))
        assertThat(days[0].day, equalTo(LocalDate.of(2026, 7, 9)))
        assertThat(days[0].visits, equalTo(1L))
        assertThat(days[1].day, equalTo(LocalDate.of(2026, 7, 11)))
        assertThat(days[1].visits, equalTo(2L))
        assertThat(days[1].engaged, equalTo(1L))
    }

    @Test
    fun `top countries counts and ranks, skipping unknowns`() {
        store.record(sampleVisit(country = "Sweden"))
        store.record(sampleVisit(country = "Sweden"))
        store.record(sampleVisit(country = "United Kingdom"))
        store.record(sampleVisit(country = null))

        assertThat(
            store.topCountries(5),
            equalTo(listOf(LabelCount("Sweden", 2), LabelCount("United Kingdom", 1))),
        )
    }

    @Test
    fun `top referrers ranks and bounds`() {
        store.record(sampleVisit(referrer = "https://github.com/damian1000"))
        store.record(sampleVisit(referrer = "https://github.com/damian1000"))
        store.record(sampleVisit(referrer = "https://linkedin.com"))

        assertThat(store.topReferrers(1), equalTo(listOf(LabelCount("https://github.com/damian1000", 2))))
    }

    @Test
    fun `engaged rate is a fraction, zero when empty`() {
        assertThat(store.engagedRate(), equalTo(0.0))
        store.record(sampleVisit(engaged = true))
        store.record(sampleVisit(engaged = false))
        store.record(sampleVisit(engaged = false))
        assertThat(store.engagedRate(), closeTo(1.0 / 3, 1e-9))
    }

    @Test
    fun `delete older than removes exactly the aged rows`() {
        store.record(sampleVisit(path = "/old", at = now.minusSeconds(86_400 * 100)))
        store.record(sampleVisit(path = "/new", at = now))

        val deleted = store.deleteOlderThan(now.minusSeconds(86_400 * 90))
        assertThat(deleted, equalTo(1))
        assertThat(store.recent(10).map { it.path }, contains("/new"))
    }
}
