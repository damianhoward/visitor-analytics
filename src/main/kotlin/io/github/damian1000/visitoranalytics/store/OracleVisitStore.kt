package io.github.damian1000.visitoranalytics.store

import io.github.damian1000.visitoranalytics.device.Device
import io.github.damian1000.visitoranalytics.geo.GeoInfo
import io.github.damian1000.visitoranalytics.model.Visit
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLIntegrityConstraintViolationException
import java.sql.Timestamp
import java.time.Clock

/**
 * Plain JDBC over the `visits` table (Flyway `V1`), one connection per operation — visits arrive
 * at human browsing rate and the dashboard is read by one person, so there is no pool to manage
 * and no connection to go stale. The SQL stays on the subset Oracle and H2's Oracle mode share:
 * unit tests run the rollups against H2, one live smoke covers real-dialect drift.
 */
class OracleVisitStore(
    private val connect: () -> Connection,
    private val clock: Clock = Clock.systemUTC(),
) : VisitStore {
    override fun record(visit: Visit) {
        try {
            insert(visit)
        } catch (_: SQLIntegrityConstraintViolationException) {
            // A replay of an already-recorded visit (tailer resume, buffer flush retry,
            // shipper refetch) — ux_visits_dedupe already holds the row.
        }
    }

    private fun insert(visit: Visit) {
        connect().use { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                statement.setString(1, visit.site)
                statement.setString(2, visit.path)
                statement.setInt(3, if (visit.engaged) 1 else 0)
                statement.setString(4, visit.geo.country)
                statement.setString(5, visit.geo.city)
                statement.setObject(6, visit.geo.asn)
                statement.setString(7, visit.geo.org)
                statement.setString(8, visit.device.browser)
                statement.setString(9, visit.device.os)
                statement.setString(10, visit.device.kind.name)
                statement.setString(11, visit.ipHash)
                statement.setString(12, visit.referrer)
                statement.setTimestamp(13, Timestamp.from(visit.at))
                statement.setString(14, visit.orgDomain)
                statement.executeUpdate()
            }
        }
    }

    override fun recent(limit: Int): List<Visit> =
        query(RECENT, { it.setInt(1, limit) }) { rows ->
            buildList {
                while (rows.next()) add(rows.toVisit())
            }
        }

    override fun visitsPerDay(days: Int): List<DayCount> =
        query(PER_DAY, { it.setTimestamp(1, Timestamp.from(clock.instant().minusSeconds(days * DAY_SECONDS))) }) { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        DayCount(
                            day = rows.getTimestamp("visit_day").toLocalDateTime().toLocalDate(),
                            visits = rows.getLong("visits"),
                            engaged = rows.getLong("engaged"),
                        ),
                    )
                }
            }
        }

    override fun topCountries(n: Int): List<LabelCount> = labelCounts(TOP_COUNTRIES, n)

    override fun topReferrers(n: Int): List<LabelCount> = labelCounts(TOP_REFERRERS, n)

    override fun engagedRate(): Double =
        query(ENGAGED_RATE, {}) { rows ->
            rows.next()
            val rate = rows.getDouble(1)
            if (rows.wasNull()) 0.0 else rate
        }

    override fun deleteOlderThan(cutoff: java.time.Instant): Int =
        connect().use { connection ->
            connection.prepareStatement(DELETE_OLD).use { statement ->
                statement.setTimestamp(1, Timestamp.from(cutoff))
                statement.executeUpdate()
            }
        }

    override fun ping(): Boolean =
        try {
            connect().use { connection ->
                connection.prepareStatement(PING).use { statement ->
                    statement.executeQuery().use { rows -> rows.next() }
                }
            }
        } catch (_: Exception) {
            false
        }

    private fun labelCounts(
        sql: String,
        n: Int,
    ): List<LabelCount> =
        query(sql, { it.setInt(1, n) }) { rows ->
            buildList {
                while (rows.next()) add(LabelCount(label = rows.getString(1), visits = rows.getLong(2)))
            }
        }

    private fun <T> query(
        sql: String,
        bind: (PreparedStatement) -> Unit,
        read: (ResultSet) -> T,
    ): T =
        connect().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement)
                statement.executeQuery().use(read)
            }
        }

    private fun ResultSet.toVisit(): Visit =
        Visit(
            site = getString("site"),
            path = getString("path"),
            engaged = getInt("engaged") == 1,
            geo =
                GeoInfo(
                    country = getString("country"),
                    city = getString("city"),
                    asn = getObject("asn")?.let { (it as Number).toLong() },
                    org = getString("org"),
                ),
            device =
                Device(
                    browser = getString("browser"),
                    os = getString("os"),
                    kind = Device.Kind.valueOf(getString("device_kind")),
                ),
            ipHash = getString("ip_hash"),
            referrer = getString("referrer"),
            at = getTimestamp("visited_at").toInstant(),
            orgDomain = getString("org_domain"),
        )

    companion object {
        private const val DAY_SECONDS = 86_400L

        private const val INSERT =
            "INSERT INTO visits (site, path, engaged, country, city, asn, org, browser, os, " +
                "device_kind, ip_hash, referrer, visited_at, org_domain) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val RECENT =
            "SELECT site, path, engaged, country, city, asn, org, browser, os, device_kind, ip_hash, referrer, visited_at, org_domain " +
                "FROM visits ORDER BY visited_at DESC FETCH FIRST ? ROWS ONLY"

        // "day" would be the natural alias, but DAY is reserved in H2 (not Oracle).
        private const val PER_DAY =
            "SELECT TRUNC(visited_at) AS visit_day, COUNT(*) AS visits, SUM(engaged) AS engaged " +
                "FROM visits WHERE visited_at >= ? GROUP BY TRUNC(visited_at) ORDER BY visit_day"
        private const val TOP_COUNTRIES =
            "SELECT country, COUNT(*) FROM visits WHERE country IS NOT NULL " +
                "GROUP BY country ORDER BY COUNT(*) DESC FETCH FIRST ? ROWS ONLY"
        private const val TOP_REFERRERS =
            "SELECT referrer, COUNT(*) FROM visits WHERE referrer IS NOT NULL " +
                "GROUP BY referrer ORDER BY COUNT(*) DESC FETCH FIRST ? ROWS ONLY"
        private const val ENGAGED_RATE = "SELECT AVG(engaged) FROM visits"
        private const val DELETE_OLD = "DELETE FROM visits WHERE visited_at < ?"
        private const val PING = "SELECT 1 FROM dual"
    }
}
