package io.github.damian1000.visitoranalytics.model

import io.github.damian1000.visitoranalytics.device.Device
import io.github.damian1000.visitoranalytics.geo.GeoInfo
import java.time.Instant

/**
 * One kept, enriched request — the unit the store records and the dashboard shows. Carries a
 * salted hash of the client address ([ipHash]), never the address itself.
 */
data class Visit(
    val site: String,
    val path: String,
    val engaged: Boolean,
    val geo: GeoInfo,
    val device: Device,
    val ipHash: String,
    val referrer: String?,
    val at: Instant,
)
