package com.couchtasks.android.viewmodel

import com.couchtasks.android.data.requireNotSwitching
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.couchtasks.android.settings.CertificatePasswordStore
import com.couchtasks.android.settings.ServerUrlValidationResult
import com.couchtasks.android.settings.SettingsRepository
import com.couchtasks.android.sync.ConfiguredHealthResult
import com.couchtasks.android.sync.ConfiguredSyncResult
import com.couchtasks.android.sync.ConfiguredSyncRunner
import com.couchtasks.android.sync.displayMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.security.UnrecoverableKeyException

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val certificatePasswordStore: CertificatePasswordStore,
    private val configuredSyncRunner: ConfiguredSyncRunner,
    val switchCoordinator: com.couchtasks.android.sync.ServerSwitchCoordinator? = null,
) : ViewModel() {
    val settings = settingsRepository.state
    val reviewIntervalDays = settingsRepository.reviewIntervalDays
    val switchJournal = settingsRepository.database.connectionDao().observeJournal()
    val switchBusy = MutableStateFlow(false)
    val switchError = MutableStateFlow("")
    val destinationUrl = MutableStateFlow("")

    init {
        viewModelScope.launch {
            switchJournal.collect { destinationUrl.value = switchCoordinator?.destinationUrl().orEmpty() }
        }
    }

    fun beginSwitch(url: String, client: String, ca: String, password: String, migrate: Boolean) = switchAction {
        requireNotNull(switchCoordinator).begin(url, client, ca, password, migrate)
    }
    fun resumeSwitch(confirm: Boolean = false) = switchAction { requireNotNull(switchCoordinator).resume(confirm) }
    fun abandonSwitch() = switchAction { requireNotNull(switchCoordinator).abandon() }
    private fun switchAction(action: suspend () -> Unit) {
        if (switchBusy.value) return
        switchBusy.value = true
        switchError.value = ""
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { switchError.value = error.message ?: "Server switch failed" }
            finally { switchBusy.value = false }
        }
    }


    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _tlsErrorDialogMessage = MutableStateFlow<String?>(null)
    val tlsErrorDialogMessage: StateFlow<String?> = _tlsErrorDialogMessage.asStateFlow()

    fun saveServerUrl(
        serverUrl: String,
        onValid: (String) -> Unit,
        onInvalid: (String) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { settingsRepository.setServerUrl(serverUrl) }
                .onSuccess { result ->
                    when (result) {
                        is ServerUrlValidationResult.Valid -> onValid(result.normalizedUrl)
                        else -> onInvalid(result.serverUrlErrorMessage())
                    }
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onInvalid(it.userVisibleMessage()) }
        }
    }

    fun saveReviewIntervalDays(text: String) {
        val days = text.trim().toIntOrNull()
        viewModelScope.launch {
            if (days == null || days < 1) {
                _messages.emit("Review interval must be a whole number of days, at least 1")
                return@launch
            }
            settingsRepository.setReviewIntervalDays(days)
            _messages.emit("Review interval saved")
        }
    }

    fun persistClientCertificate(
        uri: Uri,
        password: String,
        onSaved: () -> Unit,
        onInvalid: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val fingerprint = settingsRepository.clientCertificateSha256Fingerprint(uri, chars)
                        settingsRepository.persistClientCertificate(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            fingerprint,
                            password = chars,
                        )
                    }
                }.onSuccess {
                    onSaved()
                }.onFailure {
                    onInvalid(it.clientCertificateValidationMessage())
                }
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun persistCaCertificate(uri: Uri) {
        persistCertificate { settingsRepository.persistCaCertificate(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun healthCheck() {
        viewModelScope.launch {
            runCatching { configuredSyncRunner.healthCheck() }
                .onSuccess { result ->
                    if (result !is ConfiguredHealthResult.Success) {
                        val message = result.displayMessage()
                        if (result is ConfiguredHealthResult.TlsCertificateError) {
                            _tlsErrorDialogMessage.value = message
                        } else {
                            _messages.emit(message)
                        }
                    }
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _messages.emit(it.userVisibleMessage()) }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            runCatching { configuredSyncRunner.syncNow() }
                .onSuccess { result ->
                    if (result !is ConfiguredSyncResult.Success) {
                        val message = result.displayMessage()
                        if (result is ConfiguredSyncResult.TlsConfigurationFailure) {
                            _tlsErrorDialogMessage.value = message
                        } else {
                            _messages.emit(message)
                        }
                    }
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _messages.emit(it.userVisibleMessage()) }
        }
    }

    fun dismissTlsErrorDialog() {
        _tlsErrorDialogMessage.value = null
    }

    private fun persistCertificate(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; _messages.emit(it.userVisibleMessage()) }
        }
    }
}

fun ServerUrlValidationResult.serverUrlErrorMessage(): String = when (this) {
    is ServerUrlValidationResult.Valid -> ""
    ServerUrlValidationResult.Missing -> "Server URL is required"
    ServerUrlValidationResult.Malformed -> "Enter a valid absolute HTTPS URL"
    ServerUrlValidationResult.NotHttps -> "Server URL must start with https://"
    ServerUrlValidationResult.MissingHost -> "Server URL must include a host"
}

private fun Throwable.clientCertificateValidationMessage(): String =
    when {
        isMissingSelectedFile() -> userVisibleMessage()
        isLikelyClientCertificatePasswordError() -> "Could not decrypt the selected .p12 with that password"
        else -> userVisibleMessage()
    }

private fun Throwable.isMissingSelectedFile(): Boolean =
    this is FileNotFoundException || cause?.isMissingSelectedFile() == true

private fun Throwable.isLikelyClientCertificatePasswordError(): Boolean =
    this is IOException ||
        this is UnrecoverableKeyException ||
        cause?.isLikelyClientCertificatePasswordError() == true
