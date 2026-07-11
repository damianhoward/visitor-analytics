package io.github.damian1000.visitoranalytics.store

import io.github.damian1000.visitoranalytics.model.Visit
import java.time.Instant
import java.time.LocalDate

/** One day's traffic: everything kept, and the subset that interacted. */
data class DayCount(
    val day: LocalDate,
    val visits: Long,
    val engaged: Long,
)

/** A rollup row: a label (country, referrer) and how many visits carried it. */
data class LabelCount(
    val label: String,
    val visits: Long,
)

/** Durable home of the visit stream and the queries the dashboard renders from it. */
interface VisitStore {
    fun record(visit: Visit)

    fun recent(limit: Int): List<Visit>

    fun visitsPerDay(days: Int): List<DayCount>

    fun topCountries(n: Int): List<LabelCount>

    fun topReferrers(n: Int): List<LabelCount>

    /** Fraction of all visits that interacted (placed an order, recomputed a report); 0.0 when empty. */
    fun engagedRate(): Double

    /** Removes visits older than [cutoff]; returns how many rows went. */
    fun deleteOlderThan(cutoff: Instant): Int
}
