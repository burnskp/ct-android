package com.couchtasks.android.sync

import com.couchtasks.android.settings.ServerUrlValidationResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class ConfiguredResultMappingTest {
    @Test
    fun tlsResults_includeCauseChainInDisplayMessage() {
        val error = SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException("Trust anchor for certification path not found"))
        }

        assertEquals(
            "TLS/certificate error: SSLHandshakeException: Chain validation failed → " +
                "CertificateException: Trust anchor for certification path not found",
            ConfiguredHealthResult.TlsCertificateError(error).displayMessage(),
        )
        assertEquals(
            "TLS/certificate configuration failure: SSLHandshakeException: Chain validation failed → " +
                "CertificateException: Trust anchor for certification path not found",
            ConfiguredSyncResult.TlsConfigurationFailure(error).displayMessage(),
        )
    }

    @Test
    fun healthResults_mapTransportOutcomesToConfiguredTypes() {
        assertEquals(ConfiguredHealthResult.Success, HealthCheckResult.Success.toConfiguredHealthResult())
        assertEquals(
            ConfiguredHealthResult.InvalidUrl(ServerUrlValidationResult.NotHttps),
            HealthCheckResult.InvalidServerUrl(ServerUrlValidationResult.NotHttps).toConfiguredHealthResult(),
        )
        val healthTlsError = SSLHandshakeException("bad cert")
        assertEquals(
            ConfiguredHealthResult.TlsCertificateError(healthTlsError),
            HealthCheckResult.CertificateConfigurationError(healthTlsError).toConfiguredHealthResult(),
        )
        val healthNetworkError = IOException("timeout")
        assertEquals(
            ConfiguredHealthResult.NetworkError(healthNetworkError),
            HealthCheckResult.NetworkError(healthNetworkError).toConfiguredHealthResult(),
        )
        assertEquals(
            ConfiguredHealthResult.HttpProtocolError(404),
            HealthCheckResult.HttpProtocolError(404).toConfiguredHealthResult(),
        )
        assertEquals(
            ConfiguredHealthResult.UnexpectedBody("OK"),
            HealthCheckResult.UnexpectedBody("OK").toConfiguredHealthResult(),
        )
    }

    @Test
    fun syncResults_mapCoordinatorOutcomesToConfiguredTypes() {
        val timestamp = "2026-01-02T03:04:05Z"

        assertEquals(
            ConfiguredSyncResult.Success(checkpoint = "7", timestamp = timestamp),
            SyncRunResult.Success(SyncResponse(checkpoint = 7)).toConfiguredSyncResult { timestamp },
        )
        val syncNetworkError = IOException("timeout")
        assertEquals(
            ConfiguredSyncResult.NetworkRetryableFailure(syncNetworkError),
            SyncRunResult.RetryableFailure(SyncTransportResult.NetworkRetryableFailure(syncNetworkError))
                .toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.Http5xxFailure(503),
            SyncRunResult.RetryableFailure(SyncTransportResult.Http5xxFailure(503, "down"))
                .toConfiguredSyncResult { timestamp },
        )
        val syncTlsError = SSLHandshakeException("bad cert")
        assertEquals(
            ConfiguredSyncResult.TlsConfigurationFailure(syncTlsError),
            SyncRunResult.CertificateConfigurationFailure(
                SyncTransportResult.CertificateConfigurationError(syncTlsError),
            ).toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.Http4xxMismatch(409),
            SyncRunResult.ClientProtocolMismatch(SyncTransportResult.Http4xxMismatch(409, "conflict"))
                .toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.MalformedJsonProtocolDrift,
            SyncRunResult.MalformedJsonFailure(SyncTransportResult.MalformedJson(IllegalArgumentException("bad"), "not-json"))
                .toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.LocalStateChangedDuringSync,
            SyncRunResult.LocalStateChangedDuringSync.toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.LocalRollbackInvalidResponse,
            SyncRunResult.RolledBackInvalidResponse(InvalidSyncResponseException("missing project"))
                .toConfiguredSyncResult { timestamp },
        )
        assertEquals(
            ConfiguredSyncResult.RedirectRejected(302),
            SyncRunResult.Failed(SyncTransportResult.HttpProtocolError(302, "redirect"))
                .toConfiguredSyncResult { timestamp },
        )
    }
}
