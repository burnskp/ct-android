package com.couchtasks.android.sync

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class SyncTransportTest {
    @Test
    fun buildServiceUrl_trimsBaseUrlAndAppendsPath() {
        assertEquals(
            "https://tasks.example.com/base/healthz",
            buildServiceUrl("  https://tasks.example.com/base///  ", "/healthz"),
        )
        assertEquals(null, buildServiceUrl("https://tasks.example.com/base?x=1", "/healthz"))
    }

    @Test
    fun healthCheck_getsHealthzAndAcceptsTrimmedOkBody() = runTest {
        val seenRequests = mutableListOf<String>()
        val transport = SyncTransport(
            clientReturning { request ->
                seenRequests += "${request.method} ${request.url}"
                response(body = "OK\n")
            },
        )

        assertEquals(HealthCheckResult.Success, transport.healthCheck("https://tasks.example.com/api"))
        assertEquals(listOf("GET https://tasks.example.com/api/healthz"), seenRequests)
    }

    @Test
    fun healthCheck_reportsUnexpectedBody() = runTest {
        val transport = SyncTransport(clientReturning { response(body = "not-ok") })

        assertEquals(
            HealthCheckResult.UnexpectedBody("not-ok"),
            transport.healthCheck("https://tasks.example.com"),
        )
    }

    @Test
    fun healthCheck_classifiesTlsNetworkAndHttpErrors() = runTest {
        assertTrue(
            SyncTransport(clientThrowing(SSLHandshakeException("bad certificate")))
                .healthCheck("https://tasks.example.com") is HealthCheckResult.CertificateConfigurationError,
        )
        assertTrue(
            SyncTransport(clientThrowing(IOException("timeout")))
                .healthCheck("https://tasks.example.com") is HealthCheckResult.NetworkError,
        )
        assertEquals(
            HealthCheckResult.HttpProtocolError(404),
            SyncTransport(clientReturning { response(code = 404, body = "not found") })
                .healthCheck("https://tasks.example.com"),
        )
    }

    @Test
    fun sync_postsJsonAndDecodesResponse() = runTest {
        var seenBody = ""
        var seenContentType = ""
        var seenAccept = ""
        val transport = SyncTransport(
            clientReturning { request ->
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                seenBody = buffer.readUtf8()
                seenContentType = request.header("Content-Type").orEmpty()
                seenAccept = request.header("Accept").orEmpty()
                response(
                    body = """
                    {
                      "checkpoint": 2,
                      "projects": [],
                      "tasks": [],
                      "tombstones": []
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = transport.sync(
            "https://tasks.example.com/base",
            SyncRequest(checkpoint = 1),
        )

        assertEquals("application/json", seenContentType)
        assertEquals("application/json", seenAccept)
        assertTrue(seenBody.contains("\"checkpoint\":1"))
        assertEquals(SyncTransportResult.Success(SyncResponse(checkpoint = 2)), result)
    }

    @Test
    fun sync_reportsMalformedJson() = runTest {
        val transport = SyncTransport(clientReturning { response(body = "not-json") })

        assertTrue(
            transport.sync("https://tasks.example.com", SyncRequest()) is SyncTransportResult.MalformedJson,
        )
    }

    @Test
    fun sync_classifiesHttpTlsAndNetworkErrors() = runTest {
        val tlsTransport = SyncTransport(clientThrowing(SSLHandshakeException("bad certificate")))
        val networkTransport = SyncTransport(clientThrowing(IOException("timeout")))

        assertTrue(
            tlsTransport.sync("https://tasks.example.com", SyncRequest()) is SyncTransportResult.CertificateConfigurationError,
        )
        assertTrue(
            networkTransport.sync("https://tasks.example.com", SyncRequest()) is SyncTransportResult.NetworkRetryableFailure,
        )
        assertEquals(
            SyncTransportResult.Http4xxMismatch(409, "conflict"),
            SyncTransport(clientReturning { response(code = 409, body = "conflict") })
                .sync("https://tasks.example.com", SyncRequest()),
        )
        assertEquals(
            SyncTransportResult.Http5xxFailure(503, "down"),
            SyncTransport(clientReturning { response(code = 503, body = "down") })
                .sync("https://tasks.example.com", SyncRequest()),
        )
    }

    private fun clientReturning(handler: (okhttp3.Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> handler(chain.request()) })
        .build()

    private fun clientThrowing(error: IOException): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { throw error }
        .build()

    private fun response(
        code: Int = 200,
        body: String,
    ): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url("https://tasks.example.com").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("OK")
        .body(body.toResponseBody("text/plain".toMediaType()))
        .build()
}
