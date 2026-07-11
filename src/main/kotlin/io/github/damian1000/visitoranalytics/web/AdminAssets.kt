package io.github.damian1000.visitoranalytics.web

/**
 * The dashboard's static assets, loaded once from classpath resources at startup — HTML, CSS
 * and JS live as real files under `resources/web/` where they get highlighting and formatting,
 * not as Kotlin string literals.
 */
class AdminAssets(
    val indexHtml: String,
    val appCss: String,
    val appJs: String,
) {
    companion object {
        fun load(): AdminAssets =
            AdminAssets(
                indexHtml = resource("/web/index.html"),
                appCss = resource("/web/app.css"),
                appJs = resource("/web/app.js"),
            )

        private fun resource(path: String): String =
            checkNotNull(AdminAssets::class.java.getResource(path)) { "missing resource: $path" }.readText()
    }
}
