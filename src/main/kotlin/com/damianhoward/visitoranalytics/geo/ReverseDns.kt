package com.damianhoward.visitoranalytics.geo

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Hashtable
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext

/**
 * Resolves an address to the registrable domain of its PTR record (`gs.com`, `virginm.net`);
 * null when there is none. Never throws. Only the registrable domain is kept — full PTR
 * hostnames routinely embed the address itself (`cpc92-40-1-2.virginm.net`), which would
 * undo the no-raw-IP rule.
 */
interface ReverseDns {
    fun domain(ip: String): String?
}

/**
 * PTR lookups over JNDI's DNS provider, chosen over [InetAddress.getCanonicalHostName] for its
 * bounded timeout — a slow resolver must not stall the tail thread for more than
 * [timeoutMillis] per retry.
 */
class JndiReverseDns(
    private val timeoutMillis: Int = 1_500,
) : ReverseDns {
    override fun domain(ip: String): String? {
        val query = reverseName(ip) ?: return null
        val env = Hashtable<String, String>()
        env["java.naming.factory.initial"] = "com.sun.jndi.dns.DnsContextFactory"
        env["com.sun.jndi.dns.timeout.initial"] = timeoutMillis.toString()
        env["com.sun.jndi.dns.timeout.retries"] = "1"
        return try {
            val context = InitialDirContext(env)
            try {
                val ptr =
                    context
                        .getAttributes(query, arrayOf("PTR"))
                        .get("PTR")
                        ?.get()
                        ?.toString()
                ptr?.let(::registrableDomain)
            } finally {
                context.close()
            }
        } catch (_: NamingException) {
            null
        }
    }

    companion object {
        /** The `in-addr.arpa` / `ip6.arpa` name for an IP literal; null for anything else. */
        fun reverseName(ip: String): String? {
            // Guard against non-literals: InetAddress.getByName would resolve a hostname over
            // DNS, and log lines must never trigger a forward lookup here.
            if (!ip.matches(IPV4) && !ip.contains(':')) return null
            val address =
                try {
                    InetAddress.getByName(ip)
                } catch (_: UnknownHostException) {
                    return null
                }
            return when (address) {
                is Inet4Address -> address.address.reversed().joinToString(".") { (it.toInt() and 0xff).toString() } + ".in-addr.arpa"
                is Inet6Address ->
                    address.address
                        .flatMap { byte -> listOf((byte.toInt() shr 4) and 0x0f, byte.toInt() and 0x0f) }
                        .reversed()
                        .joinToString(".") { it.toString(16) } + ".ip6.arpa"
                else -> null
            }
        }

        /**
         * The registrable part of a hostname: the last two labels, or three when the last two
         * are a multi-part public suffix (`co.uk`). Null for names too short or non-hostname
         * PTR content.
         */
        fun registrableDomain(host: String): String? {
            val labels =
                host
                    .trim()
                    .trimEnd('.')
                    .lowercase()
                    .split('.')
                    .filter { it.isNotEmpty() }
            if (labels.size < 2 || labels.any { !it.matches(LABEL) }) return null
            val lastTwo = labels.takeLast(2).joinToString(".")
            return when {
                lastTwo !in TWO_PART_SUFFIXES -> lastTwo
                labels.size >= 3 -> labels.takeLast(3).joinToString(".")
                else -> null
            }
        }

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val LABEL = Regex("[a-z0-9_-]+")

        // The multi-part public suffixes likely to appear in visitor PTRs. Not the full public
        // suffix list: a rare miss shows co.uk-style noise for one visit, which the dashboard
        // reader can interpret — not worth a list dependency.
        private val TWO_PART_SUFFIXES =
            setOf(
                "co.uk",
                "org.uk",
                "ac.uk",
                "gov.uk",
                "net.uk",
                "me.uk",
                "co.jp",
                "ne.jp",
                "or.jp",
                "ad.jp",
                "com.au",
                "net.au",
                "org.au",
                "id.au",
                "co.nz",
                "net.nz",
                "org.nz",
                "co.za",
                "com.br",
                "net.br",
                "com.mx",
                "com.ar",
                "com.sg",
                "com.hk",
                "com.cn",
                "com.tw",
                "co.in",
                "co.kr",
                "co.il",
            )
    }
}
