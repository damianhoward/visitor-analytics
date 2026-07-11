package io.github.damian1000.visitoranalytics.ingest

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/** The fields this pipeline needs from one access-log request, before enrichment. */
data class RawRequest(
    val host: String,
    val method: String,
    val path: String,
    val status: Int,
    val remoteIp: String,
    val referrer: String?,
    val userAgent: String,
    val at: Instant,
)

/**
 * Pulls a [RawRequest] out of one Caddy JSON access-log line; anything unparseable — bad JSON, a
 * non-access log line, missing essentials — is null, and the pipeline moves on.
 *
 * The client address is Caddy's own `client_ip` (falling back to `remote_ip` on older logs),
 * never the request's `X-Forwarded-For` header: Caddy is the edge proxy here, so that header is
 * client-controlled and would let a visitor spoof their address into the analytics.
 */
class LogLineParser {
    private val mapper = ObjectMapper()

    fun parse(line: String): RawRequest? {
        val root =
            try {
                mapper.readTree(line)
            } catch (_: JacksonException) {
                return null
            }
        val request = root.path("request")
        val ts = root.path("ts")
        val status = root.path("status")
        if (!request.isObject || !ts.isNumber || !status.isIntegralNumber) return null
        return RawRequest(
            host = request.text("host") ?: return null,
            method = request.text("method") ?: return null,
            path = (request.text("uri") ?: return null).substringBefore('?'),
            status = status.asInt(),
            remoteIp = request.text("client_ip") ?: request.text("remote_ip") ?: return null,
            referrer = request.header("Referer"),
            userAgent = request.header("User-Agent") ?: "",
            at = Instant.ofEpochMilli((ts.asDouble() * 1000).toLong()),
        )
    }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()?.takeUnless { it.isEmpty() }

    // Caddy logs each header as a list of values; the first is the one browsers send.
    private fun JsonNode.header(name: String): String? =
        path("headers")
            .path(name)
            .firstOrNull()
            ?.takeIf { it.isTextual }
            ?.asText()
}
