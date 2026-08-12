package com.damianhoward.visitoranalytics.ingest

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant

class LogLineParserTest {
    private val parser = LogLineParser()

    // A real Caddy JSON access-log shape: nested request object, headers as value lists,
    // fractional epoch-seconds timestamp. Built compact (no whitespace) so tests can derive
    // variants with exact string replaces.
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0"
    private val defaultHeaders = """"User-Agent":["$ua"],"Referer":["https://github.com/damianhoward"]"""

    private fun accessLine(headers: String = defaultHeaders): String =
        listOf(
            """{"level":"info","ts":1752230400.5,"logger":"http.log.access.log0","msg":"handled request",""",
            """"request":{"remote_ip":"203.0.113.7","remote_port":"51716","client_ip":"203.0.113.7",""",
            """"proto":"HTTP/2.0","method":"GET","host":"orderbook.damianhoward.com","uri":"/?utm=x",""",
            """"headers":{$headers}},""",
            """"bytes_read":0,"user_id":"","duration":0.000123,"size":2326,"status":200}""",
        ).joinToString("")

    @Test
    fun `parses a caddy access line`() {
        val request = parser.parse(accessLine())!!
        assertThat(request.host, equalTo("orderbook.damianhoward.com"))
        assertThat(request.method, equalTo("GET"))
        assertThat(request.path, equalTo("/"))
        assertThat(request.status, equalTo(200))
        assertThat(request.remoteIp, equalTo("203.0.113.7"))
        assertThat(request.referrer, equalTo("https://github.com/damianhoward"))
        assertThat(request.userAgent, equalTo(ua))
        assertThat(request.at, equalTo(Instant.ofEpochMilli(1_752_230_400_500)))
    }

    @Test
    fun `strips the query string from the path`() {
        val line = accessLine().replace(""""uri":"/?utm=x"""", """"uri":"/report?a=1&b=2"""")
        assertThat(parser.parse(line)!!.path, equalTo("/report"))
    }

    @Test
    fun `prefers caddy's client_ip over remote_ip`() {
        val line = accessLine().replace(""""client_ip":"203.0.113.7"""", """"client_ip":"198.51.100.9"""")
        assertThat(parser.parse(line)!!.remoteIp, equalTo("198.51.100.9"))
    }

    @Test
    fun `falls back to remote_ip when client_ip is absent`() {
        val line = accessLine().replace(""""client_ip":"203.0.113.7",""", "")
        assertThat(parser.parse(line)!!.remoteIp, equalTo("203.0.113.7"))
    }

    @Test
    fun `ignores x-forwarded-for — client-controlled at the edge`() {
        val line = accessLine(headers = """"X-Forwarded-For":["10.99.99.99"],$defaultHeaders""")
        assertThat(parser.parse(line)!!.remoteIp, equalTo("203.0.113.7"))
    }

    @Test
    fun `missing user-agent becomes empty, missing referer becomes null`() {
        val request = parser.parse(accessLine(headers = """"Accept":["text/html"]"""))!!
        assertThat(request.userAgent, equalTo(""))
        assertThat(request.referrer, nullValue())
    }

    @Test
    fun `malformed json is null`() {
        assertThat(parser.parse("{\"level\":\"info\",..."), nullValue())
        assertThat(parser.parse(""), nullValue())
        assertThat(parser.parse("not json at all"), nullValue())
    }

    @Test
    fun `non-access lines are null`() {
        assertThat(parser.parse("""{"level":"info","ts":1752230400.5,"msg":"serving initial configuration"}"""), nullValue())
        assertThat(parser.parse("""{"request":{"method":"GET"},"status":"ok","ts":1.0}"""), nullValue())
    }

    @Test
    fun `a request missing essentials is null`() {
        assertThat(parser.parse(accessLine().replace(""""host":"orderbook.damianhoward.com",""", "")), nullValue())
        assertThat(parser.parse(accessLine().replace(""""method":"GET",""", "")), nullValue())
    }

    @Test
    fun `a request with neither address field is null`() {
        val line =
            accessLine()
                .replace(""""remote_ip":"203.0.113.7",""", "")
                .replace(""""client_ip":"203.0.113.7",""", "")
        assertThat(parser.parse(line), nullValue())
    }
}
