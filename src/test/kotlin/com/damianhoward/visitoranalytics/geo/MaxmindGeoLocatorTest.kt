package com.damianhoward.visitoranalytics.geo

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path

/** Runs against MaxMind's published GeoLite2 test databases, committed under test resources. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaxmindGeoLocatorTest {
    private val locator =
        MaxmindGeoLocator(
            cityDb = testResource("/maxmind/GeoLite2-City-Test.mmdb"),
            asnDb = testResource("/maxmind/GeoLite2-ASN-Test.mmdb"),
        )

    @AfterAll
    fun close() = locator.close()

    private fun testResource(path: String): Path = Path.of(javaClass.getResource(path)!!.toURI())

    @Test
    fun `resolves a covered address to city and network`() {
        // 89.160.20.128 is a documented record in both test databases: Linköping, Sweden; AS29518.
        val info = locator.locate("89.160.20.128")
        assertThat(info.country, equalTo("Sweden"))
        assertThat(info.city, equalTo("Linköping"))
        assertThat(info.asn, equalTo(29518L))
        assertThat(info.org, notNullValue())
    }

    @Test
    fun `an address only the asn database covers still gets its network`() {
        val info = locator.locate("1.128.0.1")
        assertThat(info.asn, equalTo(1221L))
        assertThat(info.org, equalTo("Telstra Pty Ltd"))
    }

    @Test
    fun `an uncovered address is unknown, not an error`() {
        assertThat(locator.locate("10.0.0.150"), equalTo(GeoInfo.UNKNOWN))
    }

    @Test
    fun `garbage input is unknown, not an error`() {
        assertThat(locator.locate("not-an-ip"), equalTo(GeoInfo.UNKNOWN))
    }

    @Test
    fun `fields resolve independently`() {
        // Covered by the city database but not the ASN one: location without network is fine.
        val info = locator.locate("2.125.160.216")
        assertThat(info.country, equalTo("United Kingdom"))
        assertThat(info.asn, nullValue())
    }
}
