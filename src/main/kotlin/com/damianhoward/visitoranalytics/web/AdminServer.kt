package com.damianhoward.visitoranalytics.web

import com.damianhoward.visitoranalytics.health.Readiness
import com.damianhoward.visitoranalytics.store.LabelCount
import com.damianhoward.visitoranalytics.store.VisitStore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * HTTP transport for the dashboard: static assets plus two JSON endpoints over [VisitStore].
 * Binds the loopback interface only — Caddy is the sole public entry, terminating TLS and
 * enforcing `basic_auth` in front of this server, so nothing here is reachable off the box.
 * A store failure (the ADB asleep or waking) maps to a 503 with a JSON error body; the
 * dashboard shows it and retries. `/healthz` proves the web process answers; `/readyz` proves the
 * database does too (the dashboard's one dependency), so a deploy that comes up before the ADB has
 * woken reads as not-ready rather than live. JDK [HttpServer] on a request pool capped at
 * [maxPoolThreads]; requests beyond the cap are refused at the connection rather than queued.
 */
class AdminServer(
    private val store: VisitStore,
    private val assets: AdminAssets,
    private val port: Int,
    // Null in tests that only exercise routing, and in that case /readyz falls back to the
    // database check alone rather than pretending to know about an ingest path that is not there.
    private val readiness: Readiness? = null,
    private val bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    private val maxPoolThreads: Int = 16,
) {
    private val mapper = ObjectMapper()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    /** Binds and starts serving; requesting port 0 binds an ephemeral port (see [boundPort]). */
    fun start() {
        server = HttpServer.create(InetSocketAddress(bindAddress, port), 0)
        // Cached-pool reuse and keep-alive but with a hard thread ceiling: an unbounded pool would
        // let a burst of concurrent requests grow threads without limit on the 96 MB box. No work
        // queue — saturation refuses the new connection instead of queuing it.
        executor =
            ThreadPoolExecutor(0, maxPoolThreads, 60L, TimeUnit.SECONDS, SynchronousQueue()) {
                Thread(it).apply { isDaemon = true }
            }
        server.executor = executor
        server.createContext("/", ::route)
        server.start()
        println("visitor-analytics admin listening on ${bindAddress.hostAddress}:$boundPort")
    }

    /** The port actually bound — differs from the requested one when 0 (ephemeral) was asked for. */
    val boundPort: Int get() = server.address.port

    /** Stops accepting connections and shuts down the request pool this server created. */
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    // HEAD rides every GET route: the handler runs identically and respond() suppresses the body,
    // so the status and headers a HEAD probe sees are the ones the GET would have produced.
    private fun route(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            exchange.responseHeaders.add("Allow", "GET, HEAD")
            return respond(exchange, 405, "text/plain", "method not allowed")
        }
        try {
            when (exchange.requestURI.path) {
                "/healthz" -> respond(exchange, 200, "text/plain", "ok")
                "/readyz" -> ready(exchange)
                "/" -> redirect(exchange, "/admin")
                "/admin" -> respond(exchange, 200, "text/html; charset=utf-8", assets.indexHtml)
                "/admin/app.css" -> respond(exchange, 200, "text/css; charset=utf-8", assets.appCss)
                "/admin/app.js" -> respond(exchange, 200, "text/javascript; charset=utf-8", assets.appJs)
                "/admin/api/visits" -> respond(exchange, 200, "application/json", visitsJson(limit(exchange)))
                "/admin/api/rollups" -> respond(exchange, 200, "application/json", rollupsJson())
                else -> respond(exchange, 404, "text/plain", "not found")
            }
        } catch (e: IllegalArgumentException) {
            respond(exchange, 400, "application/json", errorJson(e.message ?: "bad request"))
        } catch (e: Exception) {
            respond(exchange, 503, "application/json", errorJson("database unavailable: ${e.message}"))
        }
    }

    // Liveness says the web process answers; this says the database does too. [VisitStore.ping]
    // swallows its own failure into false, so a sleeping ADB reads as 503, never a dropped request.
    private fun ready(exchange: HttpExchange) {
        val probe = readiness?.probe()
        if (probe == null) {
            val ok = store.ping()
            respond(exchange, if (ok) 200 else 503, "application/json", """{"ready":$ok,"database":{"ok":$ok}}""")
        } else {
            respond(exchange, if (probe.ready) 200 else 503, "application/json", probe.json)
        }
    }

    private fun limit(exchange: HttpExchange): Int {
        val raw =
            (exchange.requestURI.rawQuery ?: "")
                .split("&")
                .firstOrNull { it.startsWith("limit=") }
                ?.substringAfter("=") ?: return DEFAULT_LIMIT
        val limit = raw.toIntOrNull() ?: throw IllegalArgumentException("limit is not a number: '$raw'")
        require(limit in 1..MAX_LIMIT) { "limit out of range: $limit" }
        return limit
    }

    private fun visitsJson(limit: Int): String {
        val visits = mapper.createArrayNode()
        // The IP hash stays server-side: the dashboard has no use for it, so it isn't exposed.
        for (visit in store.recent(limit)) {
            val node = visits.addObject()
            node.put("site", visit.site)
            node.put("path", visit.path)
            node.put("engaged", visit.engaged)
            node.put("country", visit.geo.country)
            node.put("city", visit.geo.city)
            visit.geo.asn?.let { node.put("asn", it) }
            node.put("org", visit.geo.org)
            node.put("orgDomain", visit.orgDomain)
            node.put("browser", visit.device.browser)
            node.put("os", visit.device.os)
            node.put("kind", visit.device.kind.name)
            node.put("referrer", visit.referrer)
            node.put("at", visit.at.toString())
        }
        return mapper.writeValueAsString(visits)
    }

    private fun rollupsJson(): String {
        val root = mapper.createObjectNode()
        root.put("engagedRate", store.engagedRate())
        val perDay = root.putArray("visitsPerDay")
        for (day in store.visitsPerDay(ROLLUP_DAYS)) {
            val node = perDay.addObject()
            node.put("day", day.day.toString())
            node.put("visits", day.visits)
            node.put("engaged", day.engaged)
        }
        labelCounts(root.putArray("topCountries"), store.topCountries(ROLLUP_TOP_N))
        labelCounts(root.putArray("topReferrers"), store.topReferrers(ROLLUP_TOP_N))
        return mapper.writeValueAsString(root)
    }

    private fun labelCounts(
        array: ArrayNode,
        counts: List<LabelCount>,
    ) {
        for (count in counts) {
            val node: ObjectNode = array.addObject()
            node.put("label", count.label)
            node.put("visits", count.visits)
        }
    }

    private fun errorJson(message: String): String {
        val node = mapper.createObjectNode()
        node.put("error", message)
        return mapper.writeValueAsString(node)
    }

    private fun redirect(
        exchange: HttpExchange,
        location: String,
    ) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        if (exchange.requestMethod == "HEAD") {
            // Headers only: -1 tells the JDK server no body follows, which is the one length it
            // accepts on a HEAD without logging a warning (it drops Content-Length either way).
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 500
        private const val ROLLUP_DAYS = 30
        private const val ROLLUP_TOP_N = 10
    }
}
