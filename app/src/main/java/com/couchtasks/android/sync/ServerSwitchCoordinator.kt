package com.couchtasks.android.sync

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.withTransaction
import com.couchtasks.android.data.*
import com.couchtasks.android.settings.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** The journal is the write barrier, including after process death. Source records stay untouched until activation. */
class ServerSwitchCoordinator(
    private val context: Context,
    private val db: CouchTasksDatabase,
    private val settings: SettingsRepository,
    private val passwords: CertificatePasswordStore,
    private val cancelWork: () -> Unit,
    private val exchange: (suspend (SettingsState, SyncRequest) -> SyncTransportResult)? = null,
    private val beforeActivation: suspend () -> Unit = {},
    private val caFingerprint: (String) -> String = { settings.caCertificateSha256Fingerprint(it.toUri()) },
) {
    val journal = db.connectionDao().observeJournal()

    suspend fun begin(url: String, clientUri: String, caUri: String, password: String, migrate: Boolean) {
        val canonical = (validateAndNormalizeServerUrl(url) as? ServerUrlValidationResult.Valid)?.normalizedUrl
            ?: error("Enter a valid HTTPS destination URL")
        settings.initializeProfile()
        val activeUrl = validateAndNormalizeServerUrl(settings.currentState().serverUrl)
        if ((activeUrl as? ServerUrlValidationResult.Valid)?.normalizedUrl == canonical) return
        require(clientUri.isNotBlank() && caUri.isNotBlank()) { "Select destination certificates" }
        val caSha256Fingerprint = caFingerprint(caUri)
        ConnectionOperations.stopping = true
        cancelWork()
        ConnectionOperations.activeSync?.cancel()
        try {
            ConnectionOperations.mutex.withLock {
                WriteSyncMutex.mutex.withLock {
                    db.requireNotSwitching()
                    val id = UUID.randomUUID().toString()
                    val config = SettingsState(profileId = id, serverUrl = canonical, clientCertificateUri = clientUri,
                        caCertificateUri = caUri, clientCertificateDisplayName = clientUri.substringAfterLast('/'),
                        caCertificateDisplayName = caUri.substringAfterLast('/'), caCertificateSha256Fingerprint = caSha256Fingerprint)
                    val chars = password.toCharArray()
                    try { passwords.forProfile(id).savePassword(chars) } finally { chars.fill('\u0000') }
                    db.withTransaction {
                        db.connectionDao().save(ConnectionProfile(id, SyncJson.encodeToString(SettingsState.serializer(), config)))
                        db.connectionDao().saveJournal(SwitchJournal(profileId = id, mode = if (migrate) "migrate" else "reset"))
                    }
                }
            }
        } finally { ConnectionOperations.stopping = false }
        resume()
    }

    /** Preview requires a separate explicit confirm. Upload retries use the persisted request verbatim. */
    suspend fun resume(confirmUpload: Boolean = false) = ConnectionOperations.mutex.withLock {
        // Records the destination CA fingerprint for journals written before CA pinning.
        settings.initializeProfile()
        val entry = db.connectionDao().journal() ?: return@withLock
        val config = SyncJson.decodeFromString(SettingsState.serializer(), requireNotNull(db.connectionDao().profile(entry.profileId)).settings)
        val staging = openStage(entry.profileId)
        try {
            when (entry.phase) {
                "fetch" -> {
                    // Fetch is read-only and can safely restart after any interruption.
                    staging.clearAllTablesSuspending()
                    val response = send(config, SyncRequest()).successful()
                    SyncResponseApplier(staging).apply(response)
                    if (entry.mode == "reset") {
                        db.connectionDao().saveJournal(entry.copy(phase = "activate"))
                        activate(entry, staging)
                    } else {
                        val preview = planMigration(db.projectDao().allProjects(), db.taskDao().allTasks(),
                            staging.projectDao().allProjects(), staging.taskDao().allTasks(), response.checkpoint)
                        db.connectionDao().saveJournal(entry.copy(phase = "preview",
                            request = staging.persistMigrationRequest(preview.request),
                            projectsAdded = preview.projectsAdded, tasksCopied = preview.tasksCopied, tasksSkipped = preview.tasksSkipped))
                    }
                }
                "preview", "upload" -> {
                    if (entry.phase == "preview" && !confirmUpload) return@withLock
                    val request = staging.readMigrationRequest(entry.request)
                    db.connectionDao().saveJournal(entry.copy(phase = "upload"))
                    if (staging.syncStateDao().get("switch_validated") == "1") {
                        activate(entry, staging)
                        staging.close()
                        cleanupLocked()
                        return@withLock
                    }
                    val response = send(config, request).successful()
                    // Seed copied identities so canonical project merges can move their tasks before acknowledgement.
                    staging.withTransaction {
                        request.projects.filter { it.syncDirty }.forEach { p -> staging.projectDao().upsert(ProjectEntity(p.uuid, p.name, p.nameNormalized,
                            p.createdAt, p.updatedAt, p.archivedAt, 0, true)) }
                        request.tasks.filter { it.syncDirty }.forEach { t -> staging.taskDao().upsert(TaskEntity(t.uuid, t.projectUuid, t.title, t.notes,
                            t.status, t.sortOrder.toInt(), t.createdAt, t.updatedAt, t.completedAt, 0, true, t.reviewedAt ?: t.createdAt)) }
                        SyncResponseApplier(staging).apply(response)
                        require(staging.projectDao().dirtyProjects().isEmpty() && staging.taskDao().dirtyTasks().isEmpty()) {
                            "Destination response did not acknowledge all copied records"
                        }
                        staging.syncStateDao().set(SyncStateEntity("switch_validated", "1"))
                    }
                    // The committed staging DB precedes this marker; activation can be retried without another upload.
                    db.connectionDao().saveJournal(entry.copy(phase = "activate"))
                    activate(entry, staging)
                }
                "activate" -> activate(entry, staging)
                else -> error("Unknown switch phase")
            }
        } finally { staging.close() }
        cleanupLocked()
    }

    suspend fun abandon() = ConnectionOperations.mutex.withLock {
        WriteSyncMutex.mutex.withLock { db.connectionDao().clearJournal() }
        cleanupLocked()
    }

    suspend fun destinationUrl(): String? = db.connectionDao().journal()?.let { entry ->
        db.connectionDao().profile(entry.profileId)?.let { SyncJson.decodeFromString(SettingsState.serializer(), it.settings).serverUrl }
    }

    private suspend fun activate(entry: SwitchJournal, stage: CouchTasksDatabase) = WriteSyncMutex.mutex.withLock {
        beforeActivation()
        val projects = stage.projectDao().allProjects()
        val tasks = stage.taskDao().allTasks()
        val tombstones = stage.tombstoneDao().allTombstones()
        val checkpoint = requireNotNull(stage.syncStateDao().get(SyncExporter.CHECKPOINT_KEY))
        db.withTransaction {
            db.projectDao().allProjects().forEach { db.projectDao().delete(it.uuid) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM sync_tombstones")
            db.openHelper.writableDatabase.execSQL("DELETE FROM sync_state")
            db.syncIssueDao().clear()
            projects.forEach { db.projectDao().insert(it) }
            tasks.forEach { db.taskDao().insert(it) }
            tombstones.forEach { db.tombstoneDao().upsert(it) }
            db.syncStateDao().set(SyncStateEntity(SyncExporter.CHECKPOINT_KEY, checkpoint))
            db.connectionDao().activate(ActiveProfile(profileId = entry.profileId))
            db.connectionDao().clearJournal()
        }
        settings.setSyncStatus(checkpoint, utcNowRfc3339(), "Server switch successful")
    }

    suspend fun cleanup() = ConnectionOperations.mutex.withLock { cleanupLocked() }

    private suspend fun cleanupLocked() {
        db.connectionDao().unused().forEach {
            passwords.forProfile(it.id).clearPassword()
            android.database.sqlite.SQLiteDatabase.deleteDatabase(stageFile(it.id))
            db.connectionDao().delete(it.id)
        }
        val retained = listOfNotNull(db.connectionDao().active()?.id, db.connectionDao().journal()?.profileId).toSet()
        passwords.clearUnusedProfiles(retained)
        // Active profile staging is no longer needed after activation.
        if (db.connectionDao().journal() == null) db.connectionDao().active()?.let {
            android.database.sqlite.SQLiteDatabase.deleteDatabase(stageFile(it.id))
        }
    }

    private fun stageFile(id: String) = File(context.noBackupFilesDir, "switch-$id.db")
    private fun openStage(id: String) = Room.databaseBuilder(context, CouchTasksDatabase::class.java, stageFile(id).absolutePath)
        .addMigrations(*CouchTasksDatabase.migrations()).build()

    private suspend fun send(config: SettingsState, request: SyncRequest): SyncTransportResult =
        exchange?.invoke(config, request) ?: transport(config).sync(config.serverUrl, request)

    private fun transport(config: SettingsState): SyncTransport {
        require(config.caCertificateSha256Fingerprint.isNotBlank()) { "Destination CA must be selected again; abandon and restart the switch" }
        val password = passwords.forProfile(config.profileId).loadPassword() ?: error("Destination password unavailable")
        try {
            context.contentResolver.openInputStream(config.clientCertificateUri.toUri()).use { client ->
                context.contentResolver.openInputStream(config.caCertificateUri.toUri()).use { ca ->
                    return SyncTransport(MtlsHttpClientFactory.build(requireNotNull(client), password, requireNotNull(ca), config.caCertificateSha256Fingerprint))
                }
            }
        } finally { password.fill('\u0000') }
    }
}

private fun SyncTransportResult.successful(): SyncResponse = when (this) {
    is SyncTransportResult.Success -> response
    SyncTransportResult.ResponseTooLarge -> error("Destination response exceeds 16 MiB")
    is SyncTransportResult.HttpProtocolError -> error("Destination HTTP $code; redirects are not followed")
    else -> error("Destination request failed (${this::class.java.simpleName}). Resume or abandon the switch.")
}

private suspend fun CouchTasksDatabase.clearAllTablesSuspending() = withTransaction {
    openHelper.writableDatabase.execSQL("DELETE FROM projects")
    openHelper.writableDatabase.execSQL("DELETE FROM sync_tombstones")
    openHelper.writableDatabase.execSQL("DELETE FROM sync_state")
    syncIssueDao().clear()
}

// Keep large snapshots out of a single CursorWindow row. Each base64 chunk is at most 350 KiB.
private suspend fun CouchTasksDatabase.persistMigrationRequest(request: SyncRequest): String = withTransaction {
    val bytes = SyncJson.encodeToString(SyncRequest.serializer(), request).toByteArray(Charsets.UTF_8)
    val chunkSize = 256 * 1024
    var count = 0
    for (offset in bytes.indices step chunkSize) {
        val chunk = bytes.copyOfRange(offset, minOf(offset + chunkSize, bytes.size))
        syncStateDao().set(SyncStateEntity("migration_request_${count++}", java.util.Base64.getEncoder().encodeToString(chunk)))
    }
    syncStateDao().set(SyncStateEntity("migration_request_count", count.toString()))
    bytes.sha256()
}

private suspend fun CouchTasksDatabase.readMigrationRequest(digest: String): SyncRequest {
    val count = requireNotNull(syncStateDao().get("migration_request_count")) { "Staged migration request is missing" }.toInt()
    require(count > 0) { "Staged migration request is empty" }
    val output = java.io.ByteArrayOutputStream()
    repeat(count) { index ->
        val chunk = requireNotNull(syncStateDao().get("migration_request_$index")) { "Staged migration request is incomplete" }
        output.write(java.util.Base64.getDecoder().decode(chunk))
    }
    val bytes = output.toByteArray()
    require(bytes.sha256() == digest) { "Staged migration request failed integrity verification" }
    return SyncJson.decodeFromString(SyncRequest.serializer(), bytes.toString(Charsets.UTF_8))
}

private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
