package com.couchtasks.android.sync

import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.validateAndNormalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.net.ssl.SSLException

sealed interface HealthCheckResult {
    data object ResponseTooLarge : HealthCheckResult
    data object Success : HealthCheckResult
    data class InvalidServerUrl(val validationResult: ServerUrlValidationResult) : HealthCheckResult
    data class CertificateConfigurationError(val cause: Throwable) : HealthCheckResult
    data class NetworkError(val cause: Throwable) : HealthCheckResult
    data class HttpProtocolError(val code: Int) : HealthCheckResult
    data class UnexpectedBody(val body: String) : HealthCheckResult
}

sealed interface SyncTransportResult {
    data object ResponseTooLarge : SyncTransportResult
    data class Success(val response: SyncResponse) : SyncTransportResult
    data class InvalidServerUrl(val validationResult: ServerUrlValidationResult) : SyncTransportResult
    data class CertificateConfigurationError(val cause: Throwable) : SyncTransportResult
    data class NetworkRetryableFailure(val cause: Throwable) : SyncTransportResult
    data class Http4xxMismatch(val code: Int, val body: String) : SyncTransportResult
    data class Http5xxFailure(val code: Int, val body: String) : SyncTransportResult
    data class HttpProtocolError(val code: Int, val body: String) : SyncTransportResult
    data class MalformedJson(val cause: Throwable, val body: String) : SyncTransportResult
}

class SyncTransport(
    private val client: OkHttpClient,
) {
    suspend fun healthCheck(baseUrl: String): HealthCheckResult = withContext(Dispatchers.IO) {
        val healthUrl = buildServiceUrl(baseUrl, HEALTH_PATH)
            ?: return@withContext HealthCheckResult.InvalidServerUrl(validateAndNormalizeServerUrl(baseUrl))
        val request = Request.Builder()
            .url(healthUrl)
            .get()
            .build()

        try {
            client.newCall(request).awaitBody { response ->
                val body = response.readBounded(4 * 1024)
                when {
                    !response.isSuccessful -> HealthCheckResult.HttpProtocolError(response.code)
                    body.isHealthOkBody() -> HealthCheckResult.Success
                    else -> HealthCheckResult.UnexpectedBody(body)
                }
            }
        } catch (_: ResponseLimitException) {
            HealthCheckResult.ResponseTooLarge
        } catch (error: SSLException) {
            coroutineContext.ensureActive()
            HealthCheckResult.CertificateConfigurationError(error)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            HealthCheckResult.NetworkError(error)
        }
    }

    suspend fun sync(baseUrl: String, requestBody: SyncRequest): SyncTransportResult = withContext(Dispatchers.IO) {
        val syncUrl = buildServiceUrl(baseUrl, SYNC_PATH)
            ?: return@withContext SyncTransportResult.InvalidServerUrl(validateAndNormalizeServerUrl(baseUrl))
        val encoded = SyncJson.encodeToString(SyncRequest.serializer(), requestBody)
        val request = Request.Builder()
            .url(syncUrl)
            .header("Content-Type", APPLICATION_JSON)
            .header("Accept", APPLICATION_JSON)
            .post(encoded.toRequestBody(APPLICATION_JSON.toMediaType()))
            .build()

        try {
            client.newCall(request).awaitBody { response ->
                val body = response.readBounded(if (response.code == 200) 16 * 1024 * 1024 else 16 * 1024, truncate = response.code != 200)
                when {
                    response.code == 200 -> decodeSyncResponse(body)
                    response.code in 400..499 -> SyncTransportResult.Http4xxMismatch(response.code, truncateSyncDetail(body))
                    response.code in 500..599 -> SyncTransportResult.Http5xxFailure(response.code, truncateSyncDetail(body))
                    else -> SyncTransportResult.HttpProtocolError(response.code, truncateSyncDetail(body))
                }
            }
        } catch (_: ResponseLimitException) {
            SyncTransportResult.ResponseTooLarge
        } catch (error: SSLException) {
            coroutineContext.ensureActive()
            SyncTransportResult.CertificateConfigurationError(error)
        } catch (error: IOException) {
            coroutineContext.ensureActive()
            SyncTransportResult.NetworkRetryableFailure(error)
        }
    }

    private fun decodeSyncResponse(body: String): SyncTransportResult = try {
        SyncTransportResult.Success(SyncJson.decodeFromString(SyncResponse.serializer(), body))
    } catch (error: SerializationException) {
        SyncTransportResult.MalformedJson(error, truncateSyncDetail(body))
    } catch (error: IllegalArgumentException) {
        SyncTransportResult.MalformedJson(error, truncateSyncDetail(body))
    }

    companion object {
        const val HEALTH_PATH = "healthz"
        const val SYNC_PATH = "sync"
        const val HEALTH_OK_BODY = "ok"
        const val APPLICATION_JSON = "application/json"
    }
}

private fun String.isHealthOkBody(): Boolean =
    trim().equals(SyncTransport.HEALTH_OK_BODY, ignoreCase = true)

internal fun buildServiceUrl(baseUrl: String, path: String): String? =
    when (val result = validateAndNormalizeServerUrl(baseUrl)) {
        is ServerUrlValidationResult.Valid -> result.normalizedUrl
            .toHttpUrl()
            .newBuilder()
            .addPathSegments(path.trim('/'))
            .build()
            .toString()
        else -> null
    }

private class ResponseLimitException : IOException("Response exceeds size limit")

// OkHttp exposes the decompressed source here; Content-Length is deliberately not trusted.
private fun Response.readBounded(limit: Int, truncate: Boolean = false): String {
    val source = body?.source() ?: return ""
    val buffer = Buffer()
    while (buffer.size <= limit) {
        if (source.read(buffer, minOf(8192L, limit + 1L - buffer.size)) == -1L) break
    }
    if (buffer.size > limit) {
        if (!truncate) throw ResponseLimitException()
        return buffer.readUtf8(limit.toLong()) + "… [truncated]"
    }
    return buffer.readUtf8()
}

private suspend fun <T> Call.awaitBody(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(read) }
            continuation.resumeWith(result)
        }
    })
}
