package io.github.damian1000.visitoranalytics.config

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class AppConfigTest {
    private val complete =
        mapOf(
            "CADDY_LOG_PATHS" to "/var/log/caddy/orderbook.log, /var/log/caddy/risk.log",
            "DB_URL" to "jdbc:oracle:thin:@analyticsdb_tp?TNS_ADMIN=/etc/visitor-analytics/wallet",
            "DB_USER" to "ANALYTICS",
            "DB_PASSWORD" to "secret",
            "GEOLITE_CITY_DB" to "/opt/geolite/GeoLite2-City.mmdb",
            "GEOLITE_ASN_DB" to "/opt/geolite/GeoLite2-ASN.mmdb",
            "IP_HASH_SALT" to "a-test-salt-of-sufficient-length",
            "BUFFER_PATH" to "/var/lib/visitor-analytics/buffer.jsonl",
        )

    @Test
    fun `reads a complete environment`() {
        val config = AppConfig.fromEnv(complete)
        assertThat(config.logPaths, equalTo(listOf(Path.of("/var/log/caddy/orderbook.log"), Path.of("/var/log/caddy/risk.log"))))
        assertThat(config.dbUser, equalTo("ANALYTICS"))
        assertThat(config.port, equalTo(8083))
        assertThat(config.retentionDays, equalTo(90))
    }

    @Test
    fun `port and retention are overridable`() {
        val config = AppConfig.fromEnv(complete + mapOf("PORT" to "9000", "RETENTION_DAYS" to "30"))
        assertThat(config.port, equalTo(9000))
        assertThat(config.retentionDays, equalTo(30))
    }

    @Test
    fun `each required variable fails loudly when missing`() {
        for (name in complete.keys) {
            val e = assertThrows<IllegalArgumentException> { AppConfig.fromEnv(complete - name) }
            assertThat(e.message, containsString(name))
        }
    }

    @Test
    fun `blank log paths fail`() {
        assertThrows<IllegalArgumentException> { AppConfig.fromEnv(complete + ("CADDY_LOG_PATHS" to " , ")) }
    }

    @Test
    fun `bad numbers fail`() {
        assertThrows<IllegalArgumentException> { AppConfig.fromEnv(complete + ("PORT" to "eighty")) }
        assertThrows<IllegalArgumentException> { AppConfig.fromEnv(complete + ("PORT" to "70000")) }
        assertThrows<IllegalArgumentException> { AppConfig.fromEnv(complete + ("RETENTION_DAYS" to "0")) }
    }
}
