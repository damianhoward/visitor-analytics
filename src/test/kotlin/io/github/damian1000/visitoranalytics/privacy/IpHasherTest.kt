package io.github.damian1000.visitoranalytics.privacy

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IpHasherTest {
    private val hasher = IpHasher("a-test-salt-of-sufficient-length")

    @Test
    fun `the same address always hashes the same — repeat visits are linkable`() {
        assertThat(hasher.hash("203.0.113.7"), equalTo(hasher.hash("203.0.113.7")))
    }

    @Test
    fun `different addresses hash differently`() {
        assertThat(hasher.hash("203.0.113.7"), not(equalTo(hasher.hash("203.0.113.8"))))
    }

    @Test
    fun `a different salt yields a different hash`() {
        val other = IpHasher("another-salt-of-sufficient-length")
        assertThat(hasher.hash("203.0.113.7"), not(equalTo(other.hash("203.0.113.7"))))
    }

    @Test
    fun `output is hex and carries no trace of the address`() {
        val hash = hasher.hash("203.0.113.7")
        assertThat(hash, matchesPattern("[0-9a-f]{64}"))
        assertThat(hash.contains("203"), equalTo(false))
    }

    @Test
    fun `a short salt is rejected`() {
        assertThrows<IllegalArgumentException> { IpHasher("short") }
    }
}
