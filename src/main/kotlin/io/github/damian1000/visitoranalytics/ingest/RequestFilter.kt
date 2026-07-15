package io.github.damian1000.visitoranalytics.ingest

/**
 * Decides which log lines are visits worth recording. Kept: successful page loads (GET of a
 * non-asset path) and API interactions (POST under `/api`). Dropped: health probes, static
 * assets, the per-page-load `GET /api/...` fetches (they'd double-count every page view), non-2xx
 * noise, self-declared bots, and CMS vulnerability-scan hits. [engaged] marks the kept requests
 * that show interaction — a POST is a placed order or a recompute, not just a look.
 */
class RequestFilter {
    fun keep(request: RawRequest): Boolean {
        if (request.status !in 200..299) return false
        if (BOT_TOKENS.any { it in request.userAgent.lowercase() }) return false
        if (request.isScanHit()) return false
        return when (request.method) {
            "GET" -> !request.path.isNoise()
            "POST" -> request.path.startsWith("/api")
            else -> false
        }
    }

    fun engaged(request: RawRequest): Boolean = request.method == "POST" && request.path.startsWith("/api")

    private fun String.isNoise(): Boolean =
        this == "/healthz" ||
            startsWith("/api") ||
            ASSET_SUFFIXES.any { endsWith(it) }

    // None of the sites in the estate run WordPress or any CMS, so a hit naming one of these —
    // in the path (some app servers 200 an unmatched path as an SPA fallback, not a 404) or
    // carried in via the referrer (the scanner's own redirect chain) — is a vulnerability-scanning
    // bot, never a visitor, whatever its user-agent claims to be.
    private fun RawRequest.isScanHit(): Boolean =
        SCAN_TOKENS.any { path.contains(it, ignoreCase = true) || referrer.orEmpty().contains(it, ignoreCase = true) }

    companion object {
        private val ASSET_SUFFIXES =
            listOf(".css", ".js", ".ico", ".png", ".svg", ".jpg", ".webp", ".map", ".woff", ".woff2", ".txt", ".xml")

        private val SCAN_TOKENS =
            listOf("wp-login", "wp-admin", "wp-content", "wp-json", "wp-includes", "xmlrpc.php", "wordpress")

        // Matches UserAgentClassifier's bot list; duplicated here deliberately — the filter drops,
        // the classifier labels whatever survives, and the two lists may diverge (e.g. keep
        // curl visits one day without relabelling history).
        private val BOT_TOKENS =
            listOf(
                "bot",
                "crawler",
                "spider",
                "slurp",
                "curl",
                "wget",
                "python",
                "go-http-client",
                "java/",
                "httpclient",
                "okhttp",
                "libwww",
                "scrapy",
                "headlesschrome",
                "phantomjs",
                "lighthouse",
                "pingdom",
                "uptime",
                "monitor",
            )
    }
}
