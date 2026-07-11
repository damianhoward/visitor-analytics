package io.github.damian1000.visitoranalytics.device

/** What a User-Agent header says about the visitor's client, at dashboard granularity. */
data class Device(
    val browser: String,
    val os: String,
    val kind: Kind,
) {
    enum class Kind { DESKTOP, MOBILE, BOT, UNKNOWN }
}

/**
 * A contains-based User-Agent classifier. Token order matters — every Chrome UA also says
 * "Safari", every Edge UA also says "Chrome" — so each list checks the more specific token
 * first. Dashboard granularity only; no version parsing.
 */
class UserAgentClassifier {
    fun classify(userAgent: String): Device {
        if (userAgent.isBlank()) return Device("unknown", "unknown", Device.Kind.UNKNOWN)
        val ua = userAgent.lowercase()
        if (BOT_TOKENS.any { it in ua }) return Device("bot", os(ua), Device.Kind.BOT)
        return Device(browser(ua), os(ua), if (MOBILE_TOKENS.any { it in ua }) Device.Kind.MOBILE else Device.Kind.DESKTOP)
    }

    private fun browser(ua: String): String =
        when {
            "edg/" in ua || "edge/" in ua -> "Edge"
            "opr/" in ua || "opera" in ua -> "Opera"
            "samsungbrowser" in ua -> "Samsung Internet"
            "firefox/" in ua -> "Firefox"
            "chrome/" in ua || "crios/" in ua -> "Chrome"
            "safari/" in ua -> "Safari"
            else -> "other"
        }

    private fun os(ua: String): String =
        when {
            "windows" in ua -> "Windows"
            "iphone" in ua || "ipad" in ua -> "iOS"
            "mac os x" in ua || "macintosh" in ua -> "macOS"
            "android" in ua -> "Android"
            "linux" in ua -> "Linux"
            else -> "other"
        }

    companion object {
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
        private val MOBILE_TOKENS = listOf("mobile", "iphone", "android", "ipad")
    }
}
