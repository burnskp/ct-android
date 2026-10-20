package com.couchtasks.android.sync

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit

class TransportSafetyTest {
    private val ca = HeldCertificate.Builder().commonName("Test CA").certificateAuthority(1).build()
    private val serverCertificate = HeldCertificate.Builder().commonName("Test server").signedBy(ca).addSubjectAlternativeName("localhost").build()
    private val clientCertificate = HeldCertificate.Builder().commonName("Test client").signedBy(ca).build()
    private fun client() = MtlsHttpClientFactory.build(
        ByteArrayOutputStream().also { output ->
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("client", clientCertificate.keyPair.private, "password".toCharArray(), arrayOf(clientCertificate.certificate, ca.certificate))
                store(output, "password".toCharArray())
            }
        }.toByteArray().inputStream(), "password".toCharArray(), ca.certificatePem().byteInputStream(),
    )
    private fun server(cert: HeldCertificate = serverCertificate, trustedClient: HeldCertificate = ca) = MockWebServer().apply {
        protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).addTrustedCertificate(trustedClient.certificate).build().sslSocketFactory(), false)
        requireClientAuth()
        start()
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
                    origin.enqueue(MockResponse().setResponseCode(code).setHeader("Location", target.url("/stolen")))
                    assertTrue(SyncTransport(client).sync(origin.url("/").toString(), SyncRequest()) is SyncTransportResult.HttpProtocolError)
                }
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${target.port}/stolen"))
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
            server.enqueue(MockResponse().setBody(exact))
            assertTrue(transport.sync(server.url("/").toString(), SyncRequest()) is SyncTransportResult.Success)
            val compressed = Buffer()
            GzipSink(compressed).buffer().use { it.writeUtf8(exact + " ") }
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setChunkedBody(compressed, 1024))
            assertEquals(SyncTransportResult.ResponseTooLarge, transport.sync(server.url("/").toString(), SyncRequest()))
            server.enqueue(MockResponse().setChunkedBody("ok" + " ".repeat(4094), 128))
            assertEquals(HealthCheckResult.Success, transport.healthCheck(server.url("/").toString()))
            server.enqueue(MockResponse().setBody("ok" + " ".repeat(4095)))
            assertEquals(HealthCheckResult.ResponseTooLarge, transport.healthCheck(server.url("/").toString()))
            server.enqueue(MockResponse().setResponseCode(500).setChunkedBody("x".repeat(32768), 1024))
            val error = transport.sync(server.url("/").toString(), SyncRequest()) as SyncTransportResult.Http5xxFailure
            assertTrue(error.body.length <= 16 * 1024 + 20)
        }
    }

    @Test fun cancellationDuringBodyReadCancelsCallAndPropagates() = runBlocking {
        server().use { server ->
            server.enqueue(MockResponse().setBody("{\"checkpoint\":0}").throttleBody(1, 1, TimeUnit.SECONDS))
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
}
