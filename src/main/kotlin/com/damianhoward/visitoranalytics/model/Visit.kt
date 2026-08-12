package com.damianhoward.visitoranalytics.model

import com.damianhoward.visitoranalytics.device.Device
import com.damianhoward.visitoranalytics.geo.GeoInfo
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
    val orgDomain: String? = null,
)
