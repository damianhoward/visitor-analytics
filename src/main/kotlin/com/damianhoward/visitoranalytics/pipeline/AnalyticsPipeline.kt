package com.damianhoward.visitoranalytics.pipeline

import com.damianhoward.visitoranalytics.device.UserAgentClassifier
import com.damianhoward.visitoranalytics.geo.GeoLocator
import com.damianhoward.visitoranalytics.geo.ReverseDns
import com.damianhoward.visitoranalytics.ingest.LogLineParser
import com.damianhoward.visitoranalytics.ingest.RequestFilter
import com.damianhoward.visitoranalytics.model.Visit
import com.damianhoward.visitoranalytics.privacy.IpHasher
import com.damianhoward.visitoranalytics.store.VisitStore

/**
 * The per-line flow: parse → filter → enrich (geo, device, IP hash) → record. Runs on the
 * tailer's thread; one bad line or a store hiccup is logged and skipped, never allowed to stop
 * the tail.
 */
class AnalyticsPipeline(
    private val parser: LogLineParser,
    private val filter: RequestFilter,
    private val locator: GeoLocator,
    private val reverseDns: ReverseDns,
    private val classifier: UserAgentClassifier,
    private val hasher: IpHasher,
    private val store: VisitStore,
) {
    fun onLine(line: String) {
        try {
            val request = parser.parse(line) ?: return
            if (!filter.keep(request)) return
            store.record(
                Visit(
                    site = request.host,
                    path = request.path,
                    engaged = filter.engaged(request),
                    geo = locator.locate(request.remoteIp),
                    device = classifier.classify(request.userAgent),
                    ipHash = hasher.hash(request.remoteIp),
                    referrer = request.referrer,
                    at = request.at,
                    orgDomain = reverseDns.domain(request.remoteIp),
                ),
            )
        } catch (e: Exception) {
            println("visitor-analytics: dropped a log line (${e.message})")
        }
    }
}
