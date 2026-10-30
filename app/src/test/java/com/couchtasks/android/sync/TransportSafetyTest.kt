package com.couchtasks.android.sync

import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import com.couchtasks.android.settings.sha256Fingerprint
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit

class TransportSafetyTest {
    private val ca = HeldCertificate.Builder().commonName("Test CA").certificateAuthority(1).build()
    private val serverCertificate = HeldCertificate.Builder().commonName("Test server").signedBy(ca).addSubjectAlternativeName("localhost").build()
    private val clientCertificate = HeldCertificate.Builder().commonName("Test client").signedBy(ca).build()
    private fun client(pinnedCa: HeldCertificate = ca) = MtlsHttpClientFactory.build(
        ByteArrayOutputStream().also { output ->
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("client", clientCertificate.keyPair.private, "password".toCharArray(), arrayOf(clientCertificate.certificate, ca.certificate))
                store(output, "password".toCharArray())
            }
        }.toByteArray().inputStream(), "password".toCharArray(), ca.certificatePem().byteInputStream(),
        pinnedCa.certificate.sha256Fingerprint(),
    )
    private fun server(cert: HeldCertificate = serverCertificate, trustedClient: HeldCertificate = ca) = MockWebServer().apply {
        protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).addTrustedCertificate(trustedClient.certificate).build().sslSocketFactory())
        requireClientAuth()
        start()
    }

    @Test fun replacedCaCertificateIsRejected() {
        val other = HeldCertificate.Builder().commonName("Other CA").certificateAuthority(1).build()
        assertThrows(CertificateException::class.java) { client(pinnedCa = other) }
    }

    @Test fun productionClientRejectsRedirectsAndHasDeadlines() = runBlocking {
        val client = client()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(60000, client.callTimeoutMillis)
        assertEquals(10000, client.readTimeoutMillis)
        server().use { origin ->
            server().use { target ->
                for (code in listOf(301, 302, 303, 307, 308)) {
                    origin.enqueue(MockResponse.Builder().code(code).setHeader("Location", target.url("/stolen")).build())
                    assertTrue(SyncTransport(client).sync(origin.url("/").toString(), SyncRequest()) is SyncTransportResult.HttpProtocolError)
                }
                origin.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://localhost:${target.port}/stolen").build())
                assertTrue(SyncTransport(client).sync(origin.url("/").toString(), SyncRequest()) is SyncTransportResult.HttpProtocolError)
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test fun decompressedBoundariesAndErrorDetailsAreBounded() = runBlocking {
        server().use { server ->
            val transport = SyncTransport(client())
            val json = "{\"checkpoint\":0,\"projects\":[],\"tasks\":[],\"tombstones\":[]}"
            val limit = 16 * 1024 * 1024
            val exact = json + " ".repeat(limit - json.length)
            server.enqueue(MockResponse.Builder().body(exact).build())
            assertTrue(transport.sync(server.url("/").toString(), SyncRequest()) is SyncTransportResult.Success)
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8(exact + " ") }
            server.enqueue(MockResponse.Builder().setHeader("Content-Encoding", "gzip").chunkedBody(compressed, 1024).build())
            assertEquals(SyncTransportResult.ResponseTooLarge, transport.sync(server.url("/").toString(), SyncRequest()))
            server.enqueue(MockResponse.Builder().chunkedBody("ok" + " ".repeat(4094), 128).build())
            assertEquals(HealthCheckResult.Success, transport.healthCheck(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().body("ok" + " ".repeat(4095)).build())
            assertEquals(HealthCheckResult.ResponseTooLarge, transport.healthCheck(server.url("/").toString()))
            server.enqueue(MockResponse.Builder().code(500).chunkedBody("x".repeat(32768), 1024).build())
            val error = transport.sync(server.url("/").toString(), SyncRequest()) as SyncTransportResult.Http5xxFailure
            assertTrue(error.body.length <= 16 * 1024 + 20)
        }
    }

    @Test fun cancellationDuringBodyReadCancelsCallAndPropagates() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse.Builder().body("{\"checkpoint\":0}").throttleBody(1, 1, TimeUnit.SECONDS).build())
            val client = client()
            val job = async(Dispatchers.IO) { SyncTransport(client).healthCheck(server.url("/").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            withTimeout(5000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
        }
    }

    @Test fun hostnameAndUntrustedCaAreRejected() = runBlocking {
        val wrongHost = HeldCertificate.Builder().signedBy(ca).addSubjectAlternativeName("wrong.example").build()
        server(wrongHost).use { server ->
            assertTrue(SyncTransport(client()).healthCheck(server.url("/").toString()) is HealthCheckResult.CertificateConfigurationError)
        }
        val otherCa = HeldCertificate.Builder().commonName("Test CA").certificateAuthority(1).build()
        val untrusted = HeldCertificate.Builder().signedBy(otherCa).addSubjectAlternativeName("localhost").build()
        server(untrusted).use { server ->
            assertTrue(SyncTransport(client()).healthCheck(server.url("/").toString()) is HealthCheckResult.CertificateConfigurationError)
        }
    }

    @Test fun certificateRejectionIsReportedWhenAnEarlierRouteFails() = runBlocking {
        val otherCa = HeldCertificate.Builder().commonName("Test CA").certificateAuthority(1).build()
        val untrusted = HeldCertificate.Builder().signedBy(otherCa).addSubjectAlternativeName("localhost").build()
        // An unreachable first address mirrors CI hosts where localhost resolves to ::1 before 127.0.0.1.
        val client = client().newBuilder()
            .dns { listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")) }
            .build()
        server(untrusted).use { server ->
            val transport = SyncTransport(client)
            val health = transport.healthCheck(server.url("/").toString())
            assertTrue("$health", health is HealthCheckResult.CertificateConfigurationError)
            val sync = transport.sync(server.url("/").toString(), SyncRequest())
            assertTrue("$sync", sync is SyncTransportResult.CertificateConfigurationError)
        }
    }
}
