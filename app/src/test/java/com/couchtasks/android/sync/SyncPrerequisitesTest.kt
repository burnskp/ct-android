package com.couchtasks.android.sync

import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.SettingsState
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPrerequisitesTest {
    @Test
    fun validateSyncPrerequisites_requiresServerUrlBeforeHealthCheckOrSync() {
        assertEquals(
            SyncPrerequisiteResult.MissingServerUrl,
            validateSyncPrerequisites(SettingsState(serverUrl = "   "), clientCertificatePasswordAvailable = true),
        )
    }

    @Test
    fun validateSyncPrerequisites_rejectsInvalidServerUrl() {
        assertEquals(
            SyncPrerequisiteResult.InvalidServerUrl(ServerUrlValidationResult.NotHttps),
            validateSyncPrerequisites(SettingsState(serverUrl = "http://tasks.example.com"), true),
        )
    }

    @Test
    fun validateSyncPrerequisites_requiresClientAndCaCertificates() {
        assertEquals(
            SyncPrerequisiteResult.MissingClientCertificate,
            validateSyncPrerequisites(SettingsState(serverUrl = "https://tasks.example.com"), true),
        )
        assertEquals(
            SyncPrerequisiteResult.MissingCaCertificate,
            validateSyncPrerequisites(
                SettingsState(
                    serverUrl = "https://tasks.example.com",
                    clientCertificateUri = "content://client.p12",
                ),
                true,
            ),
        )
    }

    @Test
    fun validateSyncPrerequisites_requiresPinnedCaFingerprint() {
        assertEquals(
            SyncPrerequisiteResult.MissingCaCertificate,
            validateSyncPrerequisites(
                SettingsState(
                    serverUrl = "https://tasks.example.com",
                    clientCertificateUri = "content://client.p12",
                    caCertificateUri = "content://ca.crt",
                ),
                true,
            ),
        )
    }

    @Test
    fun validateSyncPrerequisites_requiresClientCertificatePassword() {
        assertEquals(
            SyncPrerequisiteResult.MissingClientCertificatePassword,
            validateSyncPrerequisites(
                SettingsState(
                    serverUrl = "https://tasks.example.com",
                    clientCertificateUri = "content://client.p12",
                    caCertificateUri = "content://ca.crt",
                    caCertificateSha256Fingerprint = "AB:CD",
                ),
                clientCertificatePasswordAvailable = false,
            ),
        )
    }

    @Test
    fun validateSyncPrerequisites_returnsNormalizedReadyValues() {
        assertEquals(
            SyncPrerequisiteResult.Ready(
                serverUrl = "https://tasks.example.com/base",
                clientCertificateUri = "content://client.p12",
                caCertificateUri = "content://ca.crt",
                caCertificateSha256Fingerprint = "AB:CD",
            ),
            validateSyncPrerequisites(
                SettingsState(
                    serverUrl = " https://tasks.example.com/base/// ",
                    clientCertificateUri = "content://client.p12",
                    caCertificateUri = "content://ca.crt",
                    caCertificateSha256Fingerprint = "AB:CD",
                ),
                clientCertificatePasswordAvailable = true,
            ),
        )
    }
}
