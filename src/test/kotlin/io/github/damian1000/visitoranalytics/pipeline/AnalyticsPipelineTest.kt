package io.github.damian1000.visitoranalytics.pipeline

import io.github.damian1000.visitoranalytics.FakeVisitStore
import io.github.damian1000.visitoranalytics.device.Device
import io.github.damian1000.visitoranalytics.device.UserAgentClassifier
import io.github.damian1000.visitoranalytics.geo.GeoInfo
import io.github.damian1000.visitoranalytics.geo.GeoLocator
import io.github.damian1000.visitoranalytics.geo.ReverseDns
import io.github.damian1000.visitoranalytics.ingest.LogLineParser
import io.github.damian1000.visitoranalytics.ingest.RequestFilter
import io.github.damian1000.visitoranalytics.privacy.IpHasher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class AnalyticsPipelineTest {
    private val store = FakeVisitStore()
    private val geo = GeoInfo(country = "Sweden", city = "Linköping", asn = 29518L, org = "Bredband2 AB")
    private val pipeline =
        AnalyticsPipeline(
            parser = LogLineParser(),
            filter = RequestFilter(),
            locator =
                object : GeoLocator {
                    override fun locate(ip: String) = geo
                },
            reverseDns =
                object : ReverseDns {
                    override fun domain(ip: String) = "virginm.net"
                },
            classifier = UserAgentClassifier(),
            hasher = IpHasher("a-test-salt-of-sufficient-length"),
            store = store,
        )

    private fun line(
        method: String = "GET",
        uri: String = "/",
        status: Int = 200,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36",
    ) = """
        {"level":"info","ts":1752230400.5,"msg":"handled request",
         "request":{"client_ip":"203.0.113.7","method":"$method","host":"risk.damianhoward.com","uri":"$uri",
                    "headers":{"User-Agent":["$userAgent"],"Referer":["https://github.com/damian1000"]}},
         "status":$status}
        """.trimIndent().replace("\n", "")

    @Test
    fun `a kept line becomes an enriched visit`() {
        pipeline.onLine(line())

        val visit = store.recorded.single()
        assertThat(visit.site, equalTo("risk.damianhoward.com"))
        assertThat(visit.path, equalTo("/"))
        assertThat(visit.engaged, equalTo(false))
        assertThat(visit.geo, equalTo(geo))
        assertThat(visit.device, equalTo(Device("Chrome", "Windows", Device.Kind.DESKTOP)))
        assertThat(visit.ipHash, equalTo(IpHasher("a-test-salt-of-sufficient-length").hash("203.0.113.7")))
        assertThat(visit.referrer, equalTo("https://github.com/damian1000"))
        assertThat(visit.orgDomain, equalTo("virginm.net"))
    }

    @Test
    fun `an api post is an engaged visit`() {
        pipeline.onLine(line(method = "POST", uri = "/api/report"))
        assertThat(store.recorded.single().engaged, equalTo(true))
    }

    @Test
    fun `filtered and unparseable lines record nothing`() {
        pipeline.onLine(line(uri = "/healthz"))
        pipeline.onLine(line(status = 404))
        pipeline.onLine(line(userAgent = "Googlebot/2.1"))
        pipeline.onLine("not json")
        assertThat(store.recorded, empty())
    }

    @Test
    fun `a store failure is swallowed — the tail must survive`() {
        store.failWith = IllegalStateException("boom")
        pipeline.onLine(line())
        store.failWith = null
        pipeline.onLine(line())
        assertThat(store.recorded.size, equalTo(1))
    }
}
