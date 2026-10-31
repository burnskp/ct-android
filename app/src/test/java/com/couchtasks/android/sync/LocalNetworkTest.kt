package com.couchtasks.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.UnknownHostException

class LocalNetworkTest {
    private fun address(literal: String) = InetAddress.getByName(literal)

    @Test
    fun localRanges_includePrivateLinkLocalTailscaleAndUniqueLocal() {
        listOf("10.0.0.5", "172.16.1.1", "192.168.1.10", "169.254.3.4", "100.64.0.1", "100.101.102.103", "100.127.255.254", "fe80::1", "fd7a:115c:a1e0::1")
            .forEach { assertTrue(it, address(it).isLocalNetworkAddress()) }
    }

    @Test
    fun localRanges_excludePublicAndLoopbackAddresses() {
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "127.0.0.1", "::1", "2606:4700::1111")
            .forEach { assertFalse(it, address(it).isLocalNetworkAddress()) }
    }

    @Test
    fun serverUsesLocalNetwork_resolvesTheUrlHost() {
        val resolved = mutableListOf<String>()
        val resolve = { host: String -> resolved += host; listOf(address("100.100.1.2")) }

        assertTrue(serverUsesLocalNetwork("https://tasks.tailnet.ts.net:8443", resolve))
        assertEquals(listOf("tasks.tailnet.ts.net"), resolved)
        assertFalse(serverUsesLocalNetwork("https://tasks.example.com") { listOf(address("93.184.216.34")) })
    }

    @Test
    fun serverUsesLocalNetwork_isFalseForInvalidOrUnresolvableUrls() {
        assertFalse(serverUsesLocalNetwork("http://10.0.0.5") { error("not resolved") })
        assertFalse(serverUsesLocalNetwork("https://missing.invalid") { throw UnknownHostException(it) })
    }

    @Test
    fun localNetworkBlocked_findsEpermInCauseAndSuppressedRoutes() {
        val blocked = SocketException("connect failed: EPERM (Operation not permitted)")
        assertTrue(blocked.isLocalNetworkBlocked())
        assertTrue(IOException("wrapped", blocked).isLocalNetworkBlocked())
        assertTrue(ConnectException("first route").apply { addSuppressed(blocked) }.isLocalNetworkBlocked())
        assertFalse(ConnectException("Connection refused").isLocalNetworkBlocked())
    }
}
