package com.damianhoward.visitoranalytics.privacy

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Salted SHA-256 over the client address — the raw IP is never stored. The same address hashes
 * to the same value while the salt is stable, which is what repeat-visit detection needs; without
 * the salt, the 4-billion-address IPv4 space would be trivially reversible by brute force.
 */
class IpHasher(
    private val salt: String,
) {
    init {
        require(salt.length >= 16) { "IP hash salt must be at least 16 characters" }
    }

    fun hash(ip: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(StandardCharsets.UTF_8))
        digest.update(ip.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
