package com.damianhoward.visitoranalytics.config

import java.nio.file.Path

/**
 * Process configuration, read once from the environment at startup. Everything that names a
 * host-specific resource (log files, database, GeoLite2 databases, salt, buffer file) is
 * required — a misconfigured process fails at startup, not on the first visit.
 */
data class AppConfig(
    val logPaths: List<Path>,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val cityDbPath: Path,
    val asnDbPath: Path,
    val ipHashSalt: String,
    val bufferPath: Path,
    val tailerStatePath: Path,
    val port: Int,
    val retentionDays: Int,
    val internalProxies: Set<String>,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): AppConfig =
            AppConfig(
                logPaths =
                    required(env, "CADDY_LOG_PATHS")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .map { Path.of(it) }
                        .also { require(it.isNotEmpty()) { "CADDY_LOG_PATHS names no files" } },
                dbUrl = required(env, "DB_URL"),
                dbUser = required(env, "DB_USER"),
                dbPassword = required(env, "DB_PASSWORD"),
                cityDbPath = Path.of(required(env, "GEOLITE_CITY_DB")),
                asnDbPath = Path.of(required(env, "GEOLITE_ASN_DB")),
                ipHashSalt = required(env, "IP_HASH_SALT"),
                bufferPath = Path.of(required(env, "BUFFER_PATH")),
                tailerStatePath =
                    env["TAILER_STATE_PATH"]?.let { Path.of(it) }
                        ?: Path.of(required(env, "BUFFER_PATH")).resolveSibling("tailer-positions.properties"),
                port = port(env["PORT"] ?: "8083"),
                retentionDays = days(env["RETENTION_DAYS"] ?: "90"),
                // Optional: hosts in the estate that reach a site on a visitor's behalf. Unset
                // means every request is treated as a visitor's, which is what a single-site
                // deployment wants.
                internalProxies =
                    (env["INTERNAL_PROXY_IPS"] ?: "")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
            )

        private fun required(
            env: Map<String, String>,
            name: String,
        ): String = env[name]?.takeUnless { it.isBlank() } ?: throw IllegalArgumentException("$name must be set")

        private fun port(raw: String): Int {
            val port = raw.toIntOrNull() ?: throw IllegalArgumentException("PORT is not a number: '$raw'")
            require(port in 0..65535) { "PORT out of range: $port" }
            return port
        }

        private fun days(raw: String): Int {
            val days = raw.toIntOrNull() ?: throw IllegalArgumentException("RETENTION_DAYS is not a number: '$raw'")
            require(days > 0) { "RETENTION_DAYS must be positive: $days" }
            return days
        }
    }
}
