package com.couchtasks.android.settings

import com.couchtasks.android.data.*
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.Serializable
import com.couchtasks.android.sync.SyncJson
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.FileNotFoundException

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

@Serializable
data class SettingsState(
    val profileId: String = "legacy",
    val serverUrl: String = "",
    val clientCertificateUri: String = "",
    val clientCertificateDisplayName: String = "",
    val clientCertificateSizeBytes: Long? = null,
    val clientCertificateSha256Fingerprint: String = "",
    val caCertificateUri: String = "",
    val caCertificateDisplayName: String = "",
    val caCertificateSizeBytes: Long? = null,
    val caCertificateSha256Fingerprint: String = "",
    val checkpoint: String = "",
    val lastSyncTimestamp: String = "",
    val lastSyncResult: String = "Never synced",
    val lastHealthResult: String = "Health check not run",
)

data class CertificateMetadata(
    val uri: String,
    val displayName: String = "",
    val sizeBytes: Long? = null,
    val sha256Fingerprint: String = "",
)

class SettingsRepository(context: Context, val database: CouchTasksDatabase = CouchTasksDatabase.create(context)) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.settingsDataStore

    private val legacyState: Flow<SettingsState> = dataStore.data.map { preferences ->
        SettingsState(
            serverUrl = preferences[Keys.ServerUrl].orEmpty(),
            clientCertificateUri = preferences[Keys.ClientCertificateUri].orEmpty(),
            clientCertificateDisplayName = preferences[Keys.ClientCertificateDisplayName].orEmpty(),
            clientCertificateSizeBytes = preferences[Keys.ClientCertificateSizeBytes]?.toLongOrNull(),
            clientCertificateSha256Fingerprint = preferences[Keys.ClientCertificateSha256Fingerprint].orEmpty(),
            caCertificateUri = preferences[Keys.CaCertificateUri].orEmpty(),
            caCertificateDisplayName = preferences[Keys.CaCertificateDisplayName].orEmpty(),
            caCertificateSizeBytes = preferences[Keys.CaCertificateSizeBytes]?.toLongOrNull(),
            caCertificateSha256Fingerprint = preferences[Keys.CaCertificateSha256Fingerprint].orEmpty(),
            checkpoint = preferences[Keys.Checkpoint].orEmpty(),
            lastSyncTimestamp = preferences[Keys.LastSyncTimestamp].orEmpty(),
            lastSyncResult = preferences[Keys.LastSyncResult] ?: "Never synced",
            lastHealthResult = preferences[Keys.LastHealthResult] ?: "Health check not run",
        )
    }

    val state: Flow<SettingsState> = combine(legacyState, database.connectionDao().observeActive()) { legacy, active ->
        val config = active?.let { SyncJson.decodeFromString(SettingsState.serializer(), it.settings) } ?: legacy
        config.copy(checkpoint = legacy.checkpoint, lastSyncTimestamp = legacy.lastSyncTimestamp,
            lastSyncResult = legacy.lastSyncResult, lastHealthResult = legacy.lastHealthResult)
    }

    suspend fun initializeProfile() = WriteSyncMutex.mutex.withLock {
        if (database.connectionDao().active() == null) {
            val legacy = legacyState.first()
            database.withTransaction {
                database.connectionDao().save(ConnectionProfile("legacy", SyncJson.encodeToString(SettingsState.serializer(), legacy)))
                database.connectionDao().activate(ActiveProfile(profileId = "legacy"))
            }
        }
    }

    private suspend fun updateConfiguration(change: suspend (SettingsState) -> SettingsState) {
        initializeProfile()
        WriteSyncMutex.mutex.withLock {
            database.requireNotSwitching()
            val current = currentState()
            val changed = change(current)
            database.connectionDao().save(ConnectionProfile(current.profileId, SyncJson.encodeToString(SettingsState.serializer(), changed)))
        }
    }

    suspend fun setServerUrl(input: String): ServerUrlValidationResult {
        val result = validateAndNormalizeServerUrl(input)
        if (result is ServerUrlValidationResult.Valid) {
            updateConfiguration {
                require(it.serverUrl == result.normalizedUrl || (it.serverUrl.isBlank() && database.taskDao().allTasks().isEmpty())) {
                    "Use the server switch flow to change servers"
                }
                it.copy(serverUrl = result.normalizedUrl)
            }
        }
        return result
    }

    suspend fun currentState(): SettingsState = state.first()

    suspend fun persistClientCertificate(
        uri: Uri,
        permissionFlags: Int,
        sha256Fingerprint: String = "",
        password: CharArray? = null,
    ): CertificateMetadata {
        persistReadPermission(uri, permissionFlags)
        val metadata = readCertificateMetadata(uri).copy(sha256Fingerprint = sha256Fingerprint)
        updateConfiguration {
            if (password != null) CertificatePasswordStore(appContext, it.profileId).savePassword(password)
            it.copy(clientCertificateUri = metadata.uri, clientCertificateDisplayName = metadata.displayName,
            clientCertificateSizeBytes = metadata.sizeBytes, clientCertificateSha256Fingerprint = metadata.sha256Fingerprint) }
        return metadata
    }

    suspend fun persistCaCertificate(uri: Uri, permissionFlags: Int): CertificateMetadata {
        val sha256Fingerprint = caCertificateSha256Fingerprint(uri)
        persistReadPermission(uri, permissionFlags)
        val metadata = readCertificateMetadata(uri).copy(sha256Fingerprint = sha256Fingerprint)
        updateConfiguration { it.copy(caCertificateUri = metadata.uri, caCertificateDisplayName = metadata.displayName,
            caCertificateSizeBytes = metadata.sizeBytes, caCertificateSha256Fingerprint = metadata.sha256Fingerprint) }
        return metadata
    }

    suspend fun setCertificateUris(clientP12Uri: String, caCertificateUri: String) {
        updateConfiguration { it.copy(clientCertificateUri = clientP12Uri, caCertificateUri = caCertificateUri) }
    }

    fun openClientCertificateStream(uri: String) = appContext.contentResolver.openInputStream(uri.toUri())

    fun openCaCertificateStream(uri: String) = appContext.contentResolver.openInputStream(uri.toUri())

    fun clientCertificateSha256Fingerprint(uri: Uri, password: CharArray): String =
        appContext.contentResolver.openInputStream(uri)?.use {
            CertificateInspector.clientPkcs12Sha256Fingerprint(it, password)
        } ?: throw FileNotFoundException("Could not open selected client .p12")

    fun caCertificateSha256Fingerprint(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.use {
            CertificateInspector.caCertificateSha256Fingerprint(it)
        } ?: throw FileNotFoundException("Could not open selected CA certificate")

    suspend fun setSyncStatus(checkpoint: String, lastSyncTimestamp: String, lastSyncResult: String) {
        dataStore.edit {
            it[Keys.Checkpoint] = checkpoint
            it[Keys.LastSyncTimestamp] = lastSyncTimestamp
            it[Keys.LastSyncResult] = lastSyncResult
        }
    }

    suspend fun setHealthResult(lastHealthResult: String) {
        dataStore.edit { it[Keys.LastHealthResult] = lastHealthResult }
    }

    private fun persistReadPermission(uri: Uri, permissionFlags: Int) {
        val readFlag = permissionFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            appContext.contentResolver.takePersistableUriPermission(uri, readFlag)
        }
    }

    private fun readCertificateMetadata(uri: Uri): CertificateMetadata {
        var displayName = ""
        var sizeBytes: Long? = null
        runCatching { appContext.contentResolver.query(uri, null, null, null, null) }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
            }
        }
        return CertificateMetadata(
            uri = uri.toString(),
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            sizeBytes = sizeBytes,
        )
    }

    private object Keys {
        val ServerUrl = stringPreferencesKey("server_url")
        val ClientCertificateUri = stringPreferencesKey("client_certificate_uri")
        val ClientCertificateDisplayName = stringPreferencesKey("client_certificate_display_name")
        val ClientCertificateSizeBytes = stringPreferencesKey("client_certificate_size_bytes")
        val ClientCertificateSha256Fingerprint = stringPreferencesKey("client_certificate_sha256_fingerprint")
        val CaCertificateUri = stringPreferencesKey("ca_certificate_uri")
        val CaCertificateDisplayName = stringPreferencesKey("ca_certificate_display_name")
        val CaCertificateSizeBytes = stringPreferencesKey("ca_certificate_size_bytes")
        val CaCertificateSha256Fingerprint = stringPreferencesKey("ca_certificate_sha256_fingerprint")
        val Checkpoint = stringPreferencesKey("checkpoint")
        val LastSyncTimestamp = stringPreferencesKey("last_sync_timestamp")
        val LastSyncResult = stringPreferencesKey("last_sync_result")
        val LastHealthResult = stringPreferencesKey("last_health_result")
    }
}
