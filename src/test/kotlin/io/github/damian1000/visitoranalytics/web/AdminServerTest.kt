package io.github.damian1000.visitoranalytics.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damian1000.visitoranalytics.FakeVisitStore
import io.github.damian1000.visitoranalytics.sampleVisit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException

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

    private fun head(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:${server.boundPort}$path"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
