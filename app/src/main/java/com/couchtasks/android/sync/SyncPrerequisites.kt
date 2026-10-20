package com.couchtasks.android.sync

import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.SettingsState
import com.couchtasks.android.settings.validateAndNormalizeServerUrl

sealed interface SyncPrerequisiteResult {
    data class Ready(
        val serverUrl: String,
        val clientCertificateUri: String,
        val caCertificateUri: String,
    ) : SyncPrerequisiteResult

    data object MissingServerUrl : SyncPrerequisiteResult
    data class InvalidServerUrl(val validationResult: ServerUrlValidationResult) : SyncPrerequisiteResult
    data object MissingClientCertificate : SyncPrerequisiteResult
    data object MissingCaCertificate : SyncPrerequisiteResult
    data object MissingClientCertificatePassword : SyncPrerequisiteResult
}

fun validateSyncPrerequisites(
    settings: SettingsState,
    clientCertificatePasswordAvailable: Boolean,
): SyncPrerequisiteResult {
    val serverUrl = settings.serverUrl.trim()
    if (serverUrl.isEmpty()) return SyncPrerequisiteResult.MissingServerUrl
    val urlResult = validateAndNormalizeServerUrl(serverUrl)
    if (urlResult !is ServerUrlValidationResult.Valid) {
        return SyncPrerequisiteResult.InvalidServerUrl(urlResult)
    }
    if (settings.clientCertificateUri.isBlank()) return SyncPrerequisiteResult.MissingClientCertificate
    if (settings.caCertificateUri.isBlank()) return SyncPrerequisiteResult.MissingCaCertificate
    if (!clientCertificatePasswordAvailable) return SyncPrerequisiteResult.MissingClientCertificatePassword
    return SyncPrerequisiteResult.Ready(
        serverUrl = urlResult.normalizedUrl,
        clientCertificateUri = settings.clientCertificateUri,
        caCertificateUri = settings.caCertificateUri,
    )
}

fun SyncPrerequisiteResult.healthMessage(): String = when (this) {
    is SyncPrerequisiteResult.Ready -> "Ready"
    SyncPrerequisiteResult.MissingServerUrl -> "Missing server URL"
    is SyncPrerequisiteResult.InvalidServerUrl -> "Invalid server URL"
    SyncPrerequisiteResult.MissingClientCertificate -> "Missing client .p12 certificate"
    SyncPrerequisiteResult.MissingCaCertificate -> "Missing CA certificate"
    SyncPrerequisiteResult.MissingClientCertificatePassword -> "Missing or wrong .p12 password"
}
