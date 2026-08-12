package com.damianhoward.visitoranalytics.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.damianhoward.visitoranalytics.device.Device
import com.damianhoward.visitoranalytics.geo.GeoInfo
import com.damianhoward.visitoranalytics.model.Visit
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Write-ahead buffering around a [VisitStore] whose database can be away — the Always-Free ADB
 * stops after ~7 idle days and takes a minute to wake. A visit that can't be recorded is
 * appended to a local JSON-lines file; the next successful record flushes the backlog first, in
 * arrival order. [record] therefore never throws, which is what keeps the tailer thread alive
 * through an outage. Reads pass through — the dashboard shows the database's view or its error.
 */
class BufferingVisitStore(
    private val delegate: VisitStore,
    private val buffer: Path,
) : VisitStore by delegate {
    private val mapper = ObjectMapper()

    @Synchronized
    override fun record(visit: Visit) {
        val queue = pending() + visit
        var flushed = 0
        try {
            for (queued in queue) {
                delegate.record(queued)
                flushed++
            }
            Files.deleteIfExists(buffer)
        } catch (e: Exception) {
            rewrite(queue.drop(flushed))
            println("visitor-analytics: database unavailable, ${queue.size - flushed} visit(s) buffered (${e.message})")
        }
    }

    private fun pending(): List<Visit> {
        if (!Files.exists(buffer)) return emptyList()
        return Files.readAllLines(buffer, StandardCharsets.UTF_8).filter { it.isNotBlank() }.map(::fromLine)
    }

    private fun rewrite(visits: List<Visit>) {
        buffer.parent?.let(Files::createDirectories)
        Files.write(buffer, visits.map(::toLine))
    }

    private fun toLine(visit: Visit): String {
        val node = mapper.createObjectNode()
        node.put("site", visit.site)
        node.put("path", visit.path)
        node.put("engaged", visit.engaged)
        node.put("country", visit.geo.country)
        node.put("city", visit.geo.city)
        visit.geo.asn?.let { node.put("asn", it) }
        node.put("org", visit.geo.org)
        node.put("browser", visit.device.browser)
        node.put("os", visit.device.os)
        node.put("kind", visit.device.kind.name)
        node.put("ipHash", visit.ipHash)
        node.put("referrer", visit.referrer)
        node.put("at", visit.at.toEpochMilli())
        node.put("orgDomain", visit.orgDomain)
        return mapper.writeValueAsString(node)
    }

    private fun fromLine(line: String): Visit {
        val node = mapper.readTree(line)
        return Visit(
            site = node.get("site").asText(),
            path = node.get("path").asText(),
            engaged = node.get("engaged").asBoolean(),
            geo =
                GeoInfo(
                    country = node.get("country")?.takeUnless { it.isNull }?.asText(),
                    city = node.get("city")?.takeUnless { it.isNull }?.asText(),
                    asn = node.get("asn")?.takeUnless { it.isNull }?.asLong(),
                    org = node.get("org")?.takeUnless { it.isNull }?.asText(),
                ),
            device =
                Device(
                    browser = node.get("browser").asText(),
                    os = node.get("os").asText(),
                    kind = Device.Kind.valueOf(node.get("kind").asText()),
                ),
            ipHash = node.get("ipHash").asText(),
            referrer = node.get("referrer")?.takeUnless { it.isNull }?.asText(),
            at = Instant.ofEpochMilli(node.get("at").asLong()),
            orgDomain = node.get("orgDomain")?.takeUnless { it.isNull }?.asText(),
        )
    }
}
