package io.github.damian1000.visitoranalytics.geo

import com.maxmind.geoip2.DatabaseReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Path

/** What an IP address resolves to: approximate location plus the network it belongs to. */
data class GeoInfo(
    val country: String?,
    val city: String?,
    val asn: Long?,
    val org: String?,
) {
    companion object {
        val UNKNOWN = GeoInfo(null, null, null, null)
    }
}

/** Resolves an address to [GeoInfo]; never throws — an unresolvable address is [GeoInfo.UNKNOWN]. */
interface GeoLocator {
    fun locate(ip: String): GeoInfo
}

/**
 * GeoLite2 lookups over the MaxMind City and ASN databases. Addresses the databases don't cover
 * (private ranges, unallocated space) resolve to nulls field-by-field, not an error — the visit
 * is still worth recording.
 */
class MaxmindGeoLocator(
    cityDb: Path,
    asnDb: Path,
) : GeoLocator,
    AutoCloseable {
    private val city = DatabaseReader.Builder(cityDb.toFile()).build()
    private val asn = DatabaseReader.Builder(asnDb.toFile()).build()

    override fun locate(ip: String): GeoInfo {
        val address =
            try {
                // Log lines only ever carry IP literals, so this never does a DNS lookup.
                InetAddress.getByName(ip)
            } catch (_: UnknownHostException) {
                return GeoInfo.UNKNOWN
            }
        val cityResponse = city.tryCity(address)
        val asnResponse = asn.tryAsn(address)
        return GeoInfo(
            country = cityResponse.map { it.country?.name }.orElse(null),
            city = cityResponse.map { it.city?.name }.orElse(null),
            asn = asnResponse.map { it.autonomousSystemNumber }.orElse(null),
            org = asnResponse.map { it.autonomousSystemOrganization }.orElse(null),
        )
    }

    override fun close() {
        city.close()
        asn.close()
    }
}
