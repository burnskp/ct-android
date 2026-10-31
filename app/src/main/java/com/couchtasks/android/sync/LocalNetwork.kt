package com.couchtasks.android.sync

import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.validateAndNormalizeServerUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketException

/**
 * Whether reaching the server at [baseUrl] needs ACCESS_LOCAL_NETWORK when targeting API 37. Resolves the host, so call
 * it off the main thread. An unresolvable host counts as not local; the connection fails on its own either way.
 */
fun serverUsesLocalNetwork(
    baseUrl: String,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
): Boolean {
    val normalized = (validateAndNormalizeServerUrl(baseUrl) as? ServerUrlValidationResult.Valid)?.normalizedUrl ?: return false
    val addresses = runCatching { resolve(normalized.toHttpUrl().host) }.getOrElse { return false }
    return addresses.any { it.isLocalNetworkAddress() }
}

// Private, link-local, carrier-grade NAT (Tailscale's 100.64.0.0/10), and unique-local IPv6 (Tailscale's fd7a:115c:a1e0::/48).
internal fun InetAddress.isLocalNetworkAddress(): Boolean {
    if (isSiteLocalAddress || isLinkLocalAddress) return true
    val bytes = address
    return when (this) {
        is Inet4Address -> bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 0x40
        is Inet6Address -> bytes[0].toInt() and 0xfe == 0xfc
        else -> false
    }
}

// Local network protection rejects the socket with EPERM; OkHttp may hide it among suppressed route failures.
internal fun Throwable.isLocalNetworkBlocked(): Boolean {
    val seen = mutableSetOf<Throwable>()
    fun Throwable.check(): Boolean {
        if (!seen.add(this)) return false
        if (this is SocketException && message.orEmpty().let { "EPERM" in it || "Operation not permitted" in it }) return true
        return suppressed.any { it.check() } || cause?.check() == true
    }
    return check()
}
