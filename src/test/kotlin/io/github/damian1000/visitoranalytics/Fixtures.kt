package io.github.damian1000.visitoranalytics

import io.github.damian1000.visitoranalytics.device.Device
import io.github.damian1000.visitoranalytics.geo.GeoInfo
import io.github.damian1000.visitoranalytics.model.Visit
import io.github.damian1000.visitoranalytics.store.DayCount
import io.github.damian1000.visitoranalytics.store.LabelCount
import io.github.damian1000.visitoranalytics.store.VisitStore
import java.time.Instant

fun sampleVisit(
    site: String = "orderbook.damianhoward.com",
    path: String = "/",
    engaged: Boolean = false,
    country: String? = "Sweden",
    city: String? = "Linköping",
    asn: Long? = 29518L,
    org: String? = "Bredband2 AB",
    referrer: String? = "https://github.com/damian1000",
    at: Instant = Instant.parse("2026-07-11T10:00:00Z"),
    orgDomain: String? = "bredband2.se",
) = Visit(
    site = site,
    path = path,
    engaged = engaged,
    geo = GeoInfo(country = country, city = city, asn = asn, org = org),
    device = Device(browser = "Chrome", os = "Windows", kind = Device.Kind.DESKTOP),
    ipHash = "ab".repeat(32),
    referrer = referrer,
    at = at,
    orgDomain = orgDomain,
)

/** In-memory [VisitStore] that can be told to fail, for pipeline and buffering tests. */
open class FakeVisitStore : VisitStore {
    val recorded = mutableListOf<Visit>()
    var failWith: Exception? = null
    var pingOk: Boolean = true
    private var successBudget: Int? = null

    /** Accept [successes] more records, then fail each one until [heal] is called. */
    fun failAfter(successes: Int) {
        successBudget = successes
    }

    fun heal() {
        failWith = null
        successBudget = null
    }

    override fun record(visit: Visit) {
        failWith?.let { throw it }
        successBudget?.let { budget ->
            if (budget <= 0) throw java.sql.SQLException("down again")
            successBudget = budget - 1
        }
        recorded += visit
    }

    override fun recent(limit: Int): List<Visit> {
        failWith?.let { throw it }
        return recorded.takeLast(limit).reversed()
    }

    override fun visitsPerDay(days: Int): List<DayCount> {
        failWith?.let { throw it }
        return emptyList()
    }

    override fun topCountries(n: Int): List<LabelCount> = emptyList()

    override fun topReferrers(n: Int): List<LabelCount> = emptyList()

    override fun engagedRate(): Double = 0.0

    override fun deleteOlderThan(cutoff: Instant): Int = 0

    override fun ping(): Boolean = pingOk
}
