package com.damianhoward.visitoranalytics.web

import com.damianhoward.visitoranalytics.FakeVisitStore
import com.damianhoward.visitoranalytics.model.Visit
import com.damianhoward.visitoranalytics.sampleVisit
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminServerTest {
    private val store = FakeVisitStore()
    private val server = AdminServer(store, AdminAssets.load(), port = 0)
    private val client = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()

    @BeforeAll
    fun start() = server.start()

    @AfterAll
    fun stop() = server.stop()

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `healthz is ok`() {
        val response = get("/healthz")
        assertThat(response.statusCode(), equalTo(200))
        assertThat(response.body(), equalTo("ok"))
    }

    @Test
    fun `readyz is 200 when the database answers`() {
        store.pingOk = true
        val response = get("/readyz")
        assertThat(response.statusCode(), equalTo(200))
        assertThat(response.body(), containsString(""""ready":true"""))
    }

    @Test
    fun `readyz is 503 when the database cannot be reached`() {
        store.pingOk = false
        try {
            val response = get("/readyz")
            assertThat(response.statusCode(), equalTo(503))
            assertThat(response.body(), containsString(""""ready":false"""))
            assertThat(response.body(), containsString(""""database":{"ok":false}"""))
        } finally {
            store.pingOk = true
        }
    }

    @Test
    fun `root redirects to the dashboard`() {
        val response =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertThat(response.statusCode(), equalTo(302))
        assertThat(response.headers().firstValue("Location").get(), equalTo("/admin"))
    }

    @Test
    fun `serves the dashboard and its assets`() {
        assertThat(get("/admin").body(), containsString("VISITOR ANALYTICS"))
        assertThat(get("/admin/app.css").headers().firstValue("Content-Type").get(), containsString("text/css"))
        assertThat(get("/admin/app.js").headers().firstValue("Content-Type").get(), containsString("javascript"))
    }

    @Test
    fun `visits are json, newest first, without the ip hash`() {
        store.recorded.clear()
        store.record(sampleVisit(path = "/old"))
        store.record(sampleVisit(path = "/new", engaged = true))

        val response = get("/admin/api/visits?limit=10")
        assertThat(response.statusCode(), equalTo(200))
        val visits = mapper.readTree(response.body())
        assertThat(visits[0]["path"].asText(), equalTo("/new"))
        assertThat(visits[0]["engaged"].asBoolean(), equalTo(true))
        assertThat(visits[0]["country"].asText(), equalTo("Sweden"))
        assertThat(response.body(), not(containsString("abab")))
    }

    @Test
    fun `rollups carry the dashboard's numbers`() {
        val response = get("/admin/api/rollups")
        assertThat(response.statusCode(), equalTo(200))
        val rollups = mapper.readTree(response.body())
        assertThat(rollups.has("engagedRate"), equalTo(true))
        assertThat(rollups.get("visitsPerDay").isArray, equalTo(true))
        assertThat(rollups.get("topCountries").isArray, equalTo(true))
        assertThat(rollups.get("topReferrers").isArray, equalTo(true))
    }

    @Test
    fun `a bad limit is a 400 with a json error`() {
        assertThat(get("/admin/api/visits?limit=zero").statusCode(), equalTo(400))
        assertThat(get("/admin/api/visits?limit=0").statusCode(), equalTo(400))
        assertThat(get("/admin/api/visits?limit=9999").statusCode(), equalTo(400))
    }

    @Test
    fun `a store failure is a 503 with a json error, not a hang`() {
        store.failWith = null
        val failing = FakeVisitStore().apply { failWith = SQLException("ADB waking") }
        val downServer = AdminServer(failing, AdminAssets.load(), port = 0)
        downServer.start()
        try {
            val response =
                client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${downServer.boundPort}/admin/api/visits")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertThat(response.statusCode(), equalTo(503))
            assertThat(response.body(), containsString("database unavailable"))
        } finally {
            downServer.stop()
        }
    }

    @Test
    fun `unknown paths are 404, non-get is 405`() {
        assertThat(get("/admin/api/secrets").statusCode(), equalTo(404))
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}/admin"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertThat(response.statusCode(), equalTo(405))
        assertThat(response.headers().firstValue("Allow").get(), equalTo("GET, HEAD"))
    }

    @Test
    fun `HEAD answers every GET route with the GET's status and headers, minus the body`() {
        store.failWith = null
        for (path in listOf("/healthz", "/admin", "/admin/app.css", "/admin/app.js", "/admin/api/visits", "/admin/api/rollups")) {
            val head = head(path)
            assertThat(path, head.statusCode(), equalTo(get(path).statusCode()))
            assertThat(path, head.body(), equalTo(""))
        }
        assertThat(head("/admin").headers().firstValue("Content-Type").get(), containsString("text/html"))
    }

    // The bound is the reason this process survives a burst on a 96 MB box, and it is the kind of
    // limit that quietly stops working when someone swaps the queue. Saturation must refuse the
    // connection, not queue it: a queued request behind a slow database waits with no ceiling.
    // The JDK server has no status line to send once the executor rejects, so the client sees a
    // connection-level failure — that is the documented contract, matching trading-system's.
    @Test
    fun `requests beyond the thread cap are refused rather than queued`() {
        val release = CountDownLatch(1)
        val occupied = CountDownLatch(2)
        val blocking =
            object : FakeVisitStore() {
                override fun recent(limit: Int): List<Visit> {
                    occupied.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return emptyList()
                }
            }
        val bounded = AdminServer(blocking, AdminAssets.load(), port = 0, maxPoolThreads = 2)
        bounded.start()
        val holders = mutableListOf<Thread>()
        try {
            repeat(2) {
                holders +=
                    Thread {
                        runCatching {
                            HttpClient.newHttpClient().send(
                                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bounded.boundPort}/admin/api/visits")).GET().build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        }
                    }.apply {
                        isDaemon = true
                        start()
                    }
            }
            assertThat("both pool threads occupied within 5s", occupied.await(5, TimeUnit.SECONDS), equalTo(true))

            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${bounded.boundPort}/healthz")).GET().build()
            assertThrows<IOException> { client.send(request, HttpResponse.BodyHandlers.ofString()) }
        } finally {
            release.countDown()
            holders.forEach { it.join(5_000) }
            bounded.stop()
        }
    }

    private fun head(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
