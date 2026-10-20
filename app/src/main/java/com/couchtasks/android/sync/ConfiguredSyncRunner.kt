package com.couchtasks.android.sync

import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.SyncIssueEntity
import com.couchtasks.android.data.utcNowRfc3339
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.SettingsRepository
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.UnrecoverableKeyException

sealed interface ConfiguredHealthResult {
    data object SwitchInProgress : ConfiguredHealthResult
    data object ResponseTooLarge : ConfiguredHealthResult
    data object Success : ConfiguredHealthResult
    data object MissingServerUrl : ConfiguredHealthResult
    data class InvalidUrl(val validationResult: ServerUrlValidationResult) : ConfiguredHealthResult
    data object MissingClientCertificate : ConfiguredHealthResult
    data object MissingCaCertificate : ConfiguredHealthResult
    data object MissingOrWrongPassword : ConfiguredHealthResult
    data object SelectClientCertificateAgain : ConfiguredHealthResult
    data object SelectCaCertificateAgain : ConfiguredHealthResult
    data class TlsCertificateError(val cause: Throwable) : ConfiguredHealthResult
    data object NetworkError : ConfiguredHealthResult
    data class HttpProtocolError(val code: Int) : ConfiguredHealthResult
    data class UnexpectedBody(val body: String) : ConfiguredHealthResult
}

sealed interface ConfiguredSyncResult {
    data class Success(val checkpoint: String, val timestamp: String) : ConfiguredSyncResult
    data object MissingServerUrl : ConfiguredSyncResult
    data class InvalidUrl(val validationResult: ServerUrlValidationResult) : ConfiguredSyncResult
    data object MissingClientCertificate : ConfiguredSyncResult
    data object MissingCaCertificate : ConfiguredSyncResult
    data object MissingOrWrongPassword : ConfiguredSyncResult
    data object SelectClientCertificateAgain : ConfiguredSyncResult
    data object SelectCaCertificateAgain : ConfiguredSyncResult
    data class TlsConfigurationFailure(val cause: Throwable) : ConfiguredSyncResult
    data object NetworkRetryableFailure : ConfiguredSyncResult
    data object LocalStateChangedDuringSync : ConfiguredSyncResult
    data class Http4xxMismatch(val code: Int) : ConfiguredSyncResult {
        var detail: String = ""
            private set

        constructor(code: Int, detail: String) : this(code) {
            this.detail = detail
        }
    }
    data class Http5xxFailure(val code: Int) : ConfiguredSyncResult {
        var detail: String = ""
            private set

        constructor(code: Int, detail: String) : this(code) {
            this.detail = detail
        }
    }
    data object MalformedJsonProtocolDrift : ConfiguredSyncResult
    data object LocalRollbackInvalidResponse : ConfiguredSyncResult
    data object ResponseTooLarge : ConfiguredSyncResult
    data class RedirectRejected(val code: Int) : ConfiguredSyncResult
    data object SwitchInProgress : ConfiguredSyncResult
    data object Failed : ConfiguredSyncResult
}

class ConfiguredSyncRunner(
    private val db: CouchTasksDatabase,
    private val settingsRepository: SettingsRepository,
    private val passwordStore: CertificatePasswordStore,
    private val onIntervention: () -> Unit = {},
    private val scheduleTransientRetry: () -> Unit = {},
) {
    suspend fun healthCheck(): ConfiguredHealthResult = ConnectionOperations.mutex.withLock {
        if (ConnectionOperations.stopping || db.connectionDao().journal() != null) return@withLock ConfiguredHealthResult.SwitchInProgress
        settingsRepository.initializeProfile()
        ConnectionOperations.activeSync = currentCoroutineContext().job
        try { healthCheckLocked() } finally { ConnectionOperations.activeSync = null }
    }

    private suspend fun healthCheckLocked(): ConfiguredHealthResult {
        val ready = prerequisitesOrHealthFailure()?.let {
            settingsRepository.setHealthResult(it.displayMessage())
            return it
        } ?: readyPrerequisites()

        val transport = when (val build = openStreamsAndBuildTransport(ready)) {
            is BuildTransportResult.Ready -> build.transport
            BuildTransportResult.ClientCertificateUnavailable -> {
                val result = ConfiguredHealthResult.SelectClientCertificateAgain
                settingsRepository.setHealthResult(result.displayMessage())
                return result
            }
            BuildTransportResult.CaCertificateUnavailable -> {
                val result = ConfiguredHealthResult.SelectCaCertificateAgain
                settingsRepository.setHealthResult(result.displayMessage())
                return result
            }
            BuildTransportResult.PasswordUnavailableOrInvalid -> {
                val result = ConfiguredHealthResult.MissingOrWrongPassword
                settingsRepository.setHealthResult(result.displayMessage())
                return result
            }
            is BuildTransportResult.TlsConfigurationInvalid -> {
                val result = ConfiguredHealthResult.TlsCertificateError(build.cause)
                settingsRepository.setHealthResult(result.displayMessage())
                return result
            }
        }

        val result = transport.healthCheck(ready.serverUrl).toConfiguredHealthResult()
        settingsRepository.setHealthResult(result.displayMessage())
        return result
    }

    suspend fun syncNow(scheduleBackgroundRetry: Boolean = true): ConfiguredSyncResult = ConnectionOperations.mutex.withLock {
        if (ConnectionOperations.stopping || db.connectionDao().journal() != null) return@withLock ConfiguredSyncResult.SwitchInProgress
        settingsRepository.initializeProfile()
        ConnectionOperations.activeSync = currentCoroutineContext().job
        try { syncNowLocked(scheduleBackgroundRetry) } finally { ConnectionOperations.activeSync = null }
    }

    private suspend fun syncNowLocked(scheduleBackgroundRetry: Boolean): ConfiguredSyncResult {
        val ready = prerequisitesOrSyncFailure()?.let {
            settingsRepository.setSyncStatus(
                checkpoint = settingsRepository.currentState().checkpoint,
                lastSyncTimestamp = settingsRepository.currentState().lastSyncTimestamp,
                lastSyncResult = it.displayMessage(),
            )
            return it
        } ?: readyPrerequisites()

        val transport = when (val build = openStreamsAndBuildTransport(ready)) {
            is BuildTransportResult.Ready -> build.transport
            BuildTransportResult.ClientCertificateUnavailable -> {
                val result = ConfiguredSyncResult.SelectClientCertificateAgain
                val current = settingsRepository.currentState()
                settingsRepository.setSyncStatus(current.checkpoint, current.lastSyncTimestamp, result.displayMessage())
                return result
            }
            BuildTransportResult.CaCertificateUnavailable -> {
                val result = ConfiguredSyncResult.SelectCaCertificateAgain
                val current = settingsRepository.currentState()
                settingsRepository.setSyncStatus(current.checkpoint, current.lastSyncTimestamp, result.displayMessage())
                return result
            }
            BuildTransportResult.PasswordUnavailableOrInvalid -> {
                val result = ConfiguredSyncResult.MissingOrWrongPassword
                val current = settingsRepository.currentState()
                settingsRepository.setSyncStatus(current.checkpoint, current.lastSyncTimestamp, result.displayMessage())
                return result
            }
            is BuildTransportResult.TlsConfigurationInvalid -> {
                val result = ConfiguredSyncResult.TlsConfigurationFailure(build.cause)
                persistTlsIssue(build.cause)
                val current = settingsRepository.currentState()
                settingsRepository.setSyncStatus(current.checkpoint, current.lastSyncTimestamp, result.displayMessage())
                return result
            }
        }

        val coordinator = SyncCoordinator(db, transport, onIntervention = { onIntervention() })
        val result = coordinator.syncOnce(ready.serverUrl).toConfiguredSyncResult(::utcNowRfc3339)
        if (result is ConfiguredSyncResult.Success) {
            settingsRepository.setSyncStatus(result.checkpoint, result.timestamp, result.displayMessage())
        } else {
            val current = settingsRepository.currentState()
            settingsRepository.setSyncStatus(current.checkpoint, current.lastSyncTimestamp, result.displayMessage())
        }
        if (scheduleBackgroundRetry && result.isTransientFailure()) scheduleTransientRetry()
        return result
    }

    private suspend fun persistTlsIssue(cause: Throwable) {
        val checkpoint = db.syncStateDao().get(SyncExporter.CHECKPOINT_KEY)?.toLongOrNull() ?: 0
        db.syncIssueDao().upsert(
            SyncIssueEntity(
                kind = SyncIssueKind.TLS_CONFIGURATION.name,
                detail = truncateSyncDetail(cause.userVisibleTlsDetails()),
                requestCheckpoint = checkpoint,
                detectedAt = utcNowRfc3339(),
            ),
        )
        onIntervention()
    }

    private suspend fun activePasswordStore() = passwordStore.forProfile(settingsRepository.currentState().profileId)

    private suspend fun prerequisitesOrHealthFailure(): ConfiguredHealthResult? =
        when (val result = validateSyncPrerequisites(settingsRepository.currentState(), activePasswordStore().hasPassword())) {
            is SyncPrerequisiteResult.Ready -> null
            SyncPrerequisiteResult.MissingServerUrl -> ConfiguredHealthResult.MissingServerUrl
            is SyncPrerequisiteResult.InvalidServerUrl -> ConfiguredHealthResult.InvalidUrl(result.validationResult)
            SyncPrerequisiteResult.MissingClientCertificate -> ConfiguredHealthResult.MissingClientCertificate
            SyncPrerequisiteResult.MissingCaCertificate -> ConfiguredHealthResult.MissingCaCertificate
            SyncPrerequisiteResult.MissingClientCertificatePassword -> ConfiguredHealthResult.MissingOrWrongPassword
        }

    private suspend fun prerequisitesOrSyncFailure(): ConfiguredSyncResult? =
        when (val result = validateSyncPrerequisites(settingsRepository.currentState(), activePasswordStore().hasPassword())) {
            is SyncPrerequisiteResult.Ready -> null
            SyncPrerequisiteResult.MissingServerUrl -> ConfiguredSyncResult.MissingServerUrl
            is SyncPrerequisiteResult.InvalidServerUrl -> ConfiguredSyncResult.InvalidUrl(result.validationResult)
            SyncPrerequisiteResult.MissingClientCertificate -> ConfiguredSyncResult.MissingClientCertificate
            SyncPrerequisiteResult.MissingCaCertificate -> ConfiguredSyncResult.MissingCaCertificate
            SyncPrerequisiteResult.MissingClientCertificatePassword -> ConfiguredSyncResult.MissingOrWrongPassword
        }

    private suspend fun readyPrerequisites(): SyncPrerequisiteResult.Ready =
        validateSyncPrerequisites(settingsRepository.currentState(), activePasswordStore().hasPassword()) as SyncPrerequisiteResult.Ready

    private suspend fun openStreamsAndBuildTransport(ready: SyncPrerequisiteResult.Ready): BuildTransportResult =
        withContext(Dispatchers.IO) {
            val password = runCatching { activePasswordStore().loadPassword() }.getOrNull()
                ?: return@withContext BuildTransportResult.PasswordUnavailableOrInvalid
            var clientP12: InputStream? = null
            var ca: InputStream? = null
            try {
                clientP12 = runCatching { settingsRepository.openClientCertificateStream(ready.clientCertificateUri) }
                    .getOrNull()
                    ?: return@withContext BuildTransportResult.ClientCertificateUnavailable
                ca = runCatching { settingsRepository.openCaCertificateStream(ready.caCertificateUri) }
                    .getOrNull()
                    ?: return@withContext BuildTransportResult.CaCertificateUnavailable
                BuildTransportResult.Ready(SyncTransport(MtlsHttpClientFactory.build(clientP12, password, ca)))
            } catch (error: Exception) {
                if (error.isLikelyClientCertificatePasswordError()) {
                    BuildTransportResult.PasswordUnavailableOrInvalid
                } else {
                    BuildTransportResult.TlsConfigurationInvalid(error)
                }
            } finally {
                password.fill('\u0000')
                clientP12.closeQuietly()
                ca.closeQuietly()
            }
        }

    private sealed interface BuildTransportResult {
        data class Ready(val transport: SyncTransport) : BuildTransportResult
        data object ClientCertificateUnavailable : BuildTransportResult
        data object CaCertificateUnavailable : BuildTransportResult
        data object PasswordUnavailableOrInvalid : BuildTransportResult
        data class TlsConfigurationInvalid(val cause: Throwable) : BuildTransportResult
    }
}

private fun InputStream?.closeQuietly() {
    if (this == null) return
    runCatching { close() }
}

private fun Throwable.isLikelyClientCertificatePasswordError(): Boolean =
    this is IOException ||
        this is UnrecoverableKeyException ||
        cause?.isLikelyClientCertificatePasswordError() == true

private fun Throwable.userVisibleTlsDetails(): String {
    val seen = mutableSetOf<Throwable>()
    val details = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && details.size < TLS_CAUSE_CHAIN_LIMIT && seen.add(current)) {
        details += current.userVisibleTlsDetail()
        current = current.cause
    }
    return details.joinToString(" → ").truncateForStatus()
}

private fun Throwable.userVisibleTlsDetail(): String {
    val type = this::class.java.simpleName.ifBlank { "TLS error" }
    val normalizedMessage = message
        ?.lineSequence()
        ?.joinToString(" ") { it.trim() }
        ?.trim()
        .orEmpty()
    return if (normalizedMessage.isBlank()) type else "$type: $normalizedMessage"
}

private fun String.truncateForStatus(): String =
    if (length <= TLS_STATUS_MAX_LENGTH) this else take(TLS_STATUS_MAX_LENGTH - 1) + "…"

internal fun HealthCheckResult.toConfiguredHealthResult(): ConfiguredHealthResult = when (this) {
    HealthCheckResult.ResponseTooLarge -> ConfiguredHealthResult.ResponseTooLarge
    HealthCheckResult.Success -> ConfiguredHealthResult.Success
    is HealthCheckResult.InvalidServerUrl -> ConfiguredHealthResult.InvalidUrl(validationResult)
    is HealthCheckResult.CertificateConfigurationError -> ConfiguredHealthResult.TlsCertificateError(cause)
    is HealthCheckResult.NetworkError -> ConfiguredHealthResult.NetworkError
    is HealthCheckResult.HttpProtocolError -> ConfiguredHealthResult.HttpProtocolError(code)
    is HealthCheckResult.UnexpectedBody -> ConfiguredHealthResult.UnexpectedBody(body)
}

internal fun SyncRunResult.toConfiguredSyncResult(successTimestamp: () -> String): ConfiguredSyncResult = when (this) {
    is SyncRunResult.Success -> ConfiguredSyncResult.Success(response.checkpoint.toString(), successTimestamp())
    is SyncRunResult.RetryableFailure -> when (val failedTransport = transportResult) {
        is SyncTransportResult.Http5xxFailure -> ConfiguredSyncResult.Http5xxFailure(failedTransport.code, failedTransport.body)
        else -> ConfiguredSyncResult.NetworkRetryableFailure
    }
    is SyncRunResult.CertificateConfigurationFailure -> ConfiguredSyncResult.TlsConfigurationFailure(transportResult.cause)
    is SyncRunResult.ClientProtocolMismatch -> ConfiguredSyncResult.Http4xxMismatch(
        transportResult.code,
        transportResult.body,
    )
    is SyncRunResult.MalformedJsonFailure -> ConfiguredSyncResult.MalformedJsonProtocolDrift
    SyncRunResult.SwitchInProgress -> ConfiguredSyncResult.SwitchInProgress
    SyncRunResult.LocalStateChangedDuringSync -> ConfiguredSyncResult.LocalStateChangedDuringSync
    is SyncRunResult.RolledBackInvalidResponse -> ConfiguredSyncResult.LocalRollbackInvalidResponse
    is SyncRunResult.ResponseTooLarge -> ConfiguredSyncResult.ResponseTooLarge
    is SyncRunResult.Failed -> if (httpStatus in 300..399) ConfiguredSyncResult.RedirectRejected(requireNotNull(httpStatus)) else ConfiguredSyncResult.Failed
    is SyncRunResult.LocalSnapshotInvalid -> ConfiguredSyncResult.LocalRollbackInvalidResponse
}

fun ConfiguredSyncResult.isTransientFailure(): Boolean = when (this) {
    ConfiguredSyncResult.NetworkRetryableFailure,
    ConfiguredSyncResult.LocalStateChangedDuringSync,
    is ConfiguredSyncResult.Http5xxFailure -> true
    else -> false
}

fun ConfiguredHealthResult.displayMessage(): String = when (this) {
    ConfiguredHealthResult.SwitchInProgress -> "Server switch in progress; resume or abandon in Settings"
    ConfiguredHealthResult.ResponseTooLarge -> "Health response exceeds 4 KiB"
    ConfiguredHealthResult.Success -> "Healthy"
    ConfiguredHealthResult.MissingServerUrl -> "Missing server URL"
    is ConfiguredHealthResult.InvalidUrl -> "Invalid server URL"
    ConfiguredHealthResult.MissingClientCertificate -> "Missing client .p12 certificate"
    ConfiguredHealthResult.MissingCaCertificate -> "Missing CA certificate"
    ConfiguredHealthResult.MissingOrWrongPassword -> "Missing or wrong .p12 password"
    ConfiguredHealthResult.SelectClientCertificateAgain -> "Select client certificate again"
    ConfiguredHealthResult.SelectCaCertificateAgain -> "Select CA certificate again"
    is ConfiguredHealthResult.TlsCertificateError -> "TLS/certificate error: ${cause.userVisibleTlsDetails()}"
    ConfiguredHealthResult.NetworkError -> "Network error"
    is ConfiguredHealthResult.HttpProtocolError -> if (code in 300..399) "Server redirect ($code) rejected; configure the final HTTPS URL" else "HTTP/protocol error ($code)"
    is ConfiguredHealthResult.UnexpectedBody -> "Unexpected /healthz response"
}

fun ConfiguredSyncResult.displayMessage(): String = when (this) {
    is ConfiguredSyncResult.Success -> "Sync successful"
    ConfiguredSyncResult.MissingServerUrl -> "Missing server URL"
    is ConfiguredSyncResult.InvalidUrl -> "Invalid server URL"
    ConfiguredSyncResult.MissingClientCertificate -> "Missing client .p12 certificate"
    ConfiguredSyncResult.MissingCaCertificate -> "Missing CA certificate"
    ConfiguredSyncResult.MissingOrWrongPassword -> "Missing or wrong .p12 password"
    ConfiguredSyncResult.SelectClientCertificateAgain -> "Select client certificate again"
    ConfiguredSyncResult.SelectCaCertificateAgain -> "Select CA certificate again"
    is ConfiguredSyncResult.TlsConfigurationFailure -> "TLS/certificate configuration failure: ${cause.userVisibleTlsDetails()}"
    ConfiguredSyncResult.NetworkRetryableFailure -> "Network retryable failure"
    ConfiguredSyncResult.LocalStateChangedDuringSync -> "Local changes happened during sync; sync again"
    is ConfiguredSyncResult.Http4xxMismatch -> "HTTP 4xx client/protocol mismatch (${code})${detail.statusSuffix()}"
    is ConfiguredSyncResult.Http5xxFailure -> "HTTP 5xx server failure (${code})${detail.statusSuffix()}"
    ConfiguredSyncResult.MalformedJsonProtocolDrift -> "Malformed JSON/protocol drift"
    ConfiguredSyncResult.LocalRollbackInvalidResponse -> "Local rollback: invalid sync response"
    ConfiguredSyncResult.ResponseTooLarge -> "Sync response exceeds 16 MiB; no data applied"
    is ConfiguredSyncResult.RedirectRejected -> "Server redirect ($code) rejected; configure the final HTTPS URL"
    ConfiguredSyncResult.SwitchInProgress -> "Server switch in progress; resume or abandon in Settings"
    ConfiguredSyncResult.Failed -> "Sync failed"
}

private const val TLS_CAUSE_CHAIN_LIMIT = 5
private const val TLS_STATUS_MAX_LENGTH = 500

private fun String.statusSuffix(): String = ifBlank { "" }.let { if (it.isEmpty()) it else ": $it" }
