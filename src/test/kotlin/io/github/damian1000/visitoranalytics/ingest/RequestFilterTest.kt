package io.github.damian1000.visitoranalytics.ingest

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Instant

class RequestFilterTest {
    private val filter = RequestFilter()

    private fun request(
        method: String = "GET",
        path: String = "/",
        status: Int = 200,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0",
    ) = RawRequest(
        host = "orderbook.damianhoward.com",
        method = method,
        path = path,
        status = status,
        remoteIp = "203.0.113.7",
        referrer = null,
        userAgent = userAgent,
        at = Instant.parse("2026-07-11T10:00:00Z"),
    )

    @Test
    fun `keeps page loads`() {
        assertThat(filter.keep(request(path = "/")), equalTo(true))
    }

    @Test
    fun `keeps api posts and marks them engaged`() {
        val order = request(method = "POST", path = "/api/order")
        assertThat(filter.keep(order), equalTo(true))
        assertThat(filter.engaged(order), equalTo(true))
    }

    @Test
    fun `page loads are not engaged`() {
        assertThat(filter.engaged(request(path = "/")), equalTo(false))
    }

    @Test
    fun `drops the per-page-load api fetches`() {
        assertThat(filter.keep(request(method = "GET", path = "/api/report")), equalTo(false))
    }

    @Test
    fun `drops health probes and static assets`() {
        assertThat(filter.keep(request(path = "/healthz")), equalTo(false))
        assertThat(filter.keep(request(path = "/app.css")), equalTo(false))
        assertThat(filter.keep(request(path = "/app.js")), equalTo(false))
        assertThat(filter.keep(request(path = "/favicon.ico")), equalTo(false))
        assertThat(filter.keep(request(path = "/robots.txt")), equalTo(false))
    }

    @Test
    fun `drops non-2xx`() {
        assertThat(filter.keep(request(status = 404)), equalTo(false))
        assertThat(filter.keep(request(status = 500)), equalTo(false))
        assertThat(filter.keep(request(status = 302)), equalTo(false))
    }

    @Test
    fun `drops self-declared bots`() {
        assertThat(filter.keep(request(userAgent = "Mozilla/5.0 (compatible; Googlebot/2.1)")), equalTo(false))
        assertThat(filter.keep(request(userAgent = "curl/8.5.0")), equalTo(false))
        assertThat(filter.keep(request(userAgent = "python-requests/2.32.0")), equalTo(false))
    }

    @Test
    fun `drops methods that are neither get nor post`() {
        assertThat(filter.keep(request(method = "HEAD")), equalTo(false))
        assertThat(filter.keep(request(method = "OPTIONS", path = "/api/order")), equalTo(false))
    }

    @Test
    fun `drops posts outside the api`() {
        assertThat(filter.keep(request(method = "POST", path = "/login")), equalTo(false))
    }
}
