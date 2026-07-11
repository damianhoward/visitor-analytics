package io.github.damian1000.visitoranalytics.geo

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

class ReverseDnsTest {
    @Test
    fun `ipv4 reverse name is octet-reversed in-addr arpa`() {
        assertThat(JndiReverseDns.reverseName("203.0.113.7"), equalTo("7.113.0.203.in-addr.arpa"))
    }

    @Test
    fun `ipv6 reverse name is nibble-reversed ip6 arpa`() {
        assertThat(
            JndiReverseDns.reverseName("2001:db8::1"),
            equalTo("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa"),
        )
    }

    @Test
    fun `a hostname is refused - no forward lookups from log data`() {
        assertThat(JndiReverseDns.reverseName("example.com"), nullValue())
        assertThat(JndiReverseDns.reverseName("not an ip"), nullValue())
    }

    @Test
    fun `registrable domain keeps two labels and drops the host part`() {
        assertThat(JndiReverseDns.registrableDomain("cpc92040-watf10.virginm.net."), equalTo("virginm.net"))
        assertThat(JndiReverseDns.registrableDomain("dns.google"), equalTo("dns.google"))
    }

    @Test
    fun `registrable domain keeps three labels on a two-part public suffix`() {
        assertThat(JndiReverseDns.registrableDomain("mail.bigbank.co.uk"), equalTo("bigbank.co.uk"))
        assertThat(JndiReverseDns.registrableDomain("co.uk"), nullValue())
    }

    @Test
    fun `non-hostname ptr content is rejected`() {
        assertThat(JndiReverseDns.registrableDomain("localhost"), nullValue())
        assertThat(JndiReverseDns.registrableDomain("bad host.example.com"), nullValue())
    }

    @Test
    fun `a non-literal input resolves to null without a lookup`() {
        assertThat(JndiReverseDns().domain("not-an-ip"), nullValue())
    }

    @Test
    fun `an address with no ptr record resolves to null`() {
        // 192.0.2.0/24 is TEST-NET-1: reserved for documentation, never delegated a PTR.
        assertThat(JndiReverseDns().domain("192.0.2.1"), nullValue())
    }

    @Test
    fun `a real ptr resolves to its registrable domain`() {
        // Google's public resolver has carried this PTR for over a decade; if this ever fails
        // with a network-less environment, it is the one test here that needs real DNS.
        assertThat(JndiReverseDns().domain("8.8.8.8"), equalTo("dns.google"))
    }
}
