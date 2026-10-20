package com.couchtasks.android.sync

import com.couchtasks.android.data.SyncIssueEntity
import java.security.MessageDigest
import java.util.UUID

enum class SyncIssueKind {
    CHECKPOINT_AHEAD,
    PROJECT_NAME_COLLISION,
    ORPHAN_TASK,
    INVALID_SNAPSHOT,
    PAYLOAD_TOO_LARGE,
    REQUEST_CONFIGURATION,
    CLIENT_SERVER_INCOMPATIBLE,
    UNKNOWN_CONFLICT,
    TLS_CONFIGURATION,
}

data class ClassifiedSyncIssue(
    val kind: SyncIssueKind,
    val entityUuid: String? = null,
    val relatedUuid: String? = null,
    val serverCheckpoint: Long? = null,
)

sealed interface SyncResult {
    data class Applied(val checkpoint: Long) : SyncResult

    data class NeedsIntervention(
        val kind: SyncIssueKind,
        val httpStatus: Int?,
        val detail: String,
    ) : SyncResult

    data class RetryLater(val detail: String) : SyncResult
}

fun SyncRunResult.toSyncResult(): SyncResult = when (this) {
    is SyncRunResult.Success -> SyncResult.Applied(response.checkpoint)
    is SyncRunResult.NeedsIntervention -> SyncResult.NeedsIntervention(kind, httpStatus, detail)
    is SyncRunResult.RetryableFailure -> SyncResult.RetryLater(transportResult.retryDetail())
    SyncRunResult.SwitchInProgress -> SyncResult.RetryLater("Server switch in progress; resume or abandon in Settings")
    SyncRunResult.LocalStateChangedDuringSync -> SyncResult.RetryLater("Local data changed during sync")
}

private fun SyncTransportResult.retryDetail(): String = when (this) {
    is SyncTransportResult.NetworkRetryableFailure -> cause.message.orEmpty()
    is SyncTransportResult.Http5xxFailure -> body
    else -> "Sync can be retried"
}

private val checkpointAheadPattern = Regex(
    "^client checkpoint ([0-9]+) is ahead of server checkpoint ([0-9]+); sync another current client or rebuild the server\\s*$",
)
private val projectCollisionPattern = Regex(
    "^project ([^\\s]+) cannot be renamed to normalized name \\\"[^\\\"\\r\\n]*\\\" because it belongs to project ([^;\\s]+); choose a distinct project name\\s*$",
)
private val orphanTaskPattern = Regex(
    "^task ([^\\s]+) references missing project ([^;\\s]+); repair it with `ct update ([^\\s]+) --project <valid-project>` or delete it with `ct delete ([^\\s]+)`\\s*$",
)

fun classifyConflict(detail: String, requestCheckpoint: Long? = null): ClassifiedSyncIssue {
    checkpointAheadPattern.matchEntire(detail)?.let { match ->
        val client = match.groupValues[1].toLongOrNull()
        val server = match.groupValues[2].toLongOrNull()
        if (client != null && server != null && (requestCheckpoint == null || client == requestCheckpoint)) {
            return ClassifiedSyncIssue(
                kind = SyncIssueKind.CHECKPOINT_AHEAD,
                serverCheckpoint = server,
            )
        }
    }
    projectCollisionPattern.matchEntire(detail)?.let { match ->
        val project = match.groupValues[1].validUuidOrNull()
        val other = match.groupValues[2].validUuidOrNull()
        if (project != null && other != null) {
            return ClassifiedSyncIssue(SyncIssueKind.PROJECT_NAME_COLLISION, project, other)
        }
    }
    orphanTaskPattern.matchEntire(detail)?.let { match ->
        val task = match.groupValues[1].validUuidOrNull()
        val missingProject = match.groupValues[2].validUuidOrNull()
        val updateTask = match.groupValues[3].validUuidOrNull()
        val deleteTask = match.groupValues[4].validUuidOrNull()
        if (task != null && missingProject != null && task == updateTask && task == deleteTask) {
            return ClassifiedSyncIssue(SyncIssueKind.ORPHAN_TASK, task, missingProject)
        }
    }
    return ClassifiedSyncIssue(SyncIssueKind.UNKNOWN_CONFLICT)
}

fun classifyRejectedRequest(status: Int): SyncIssueKind = when (status) {
    400 -> SyncIssueKind.INVALID_SNAPSHOT
    409 -> SyncIssueKind.UNKNOWN_CONFLICT
    413 -> SyncIssueKind.PAYLOAD_TOO_LARGE
    415 -> SyncIssueKind.REQUEST_CONFIGURATION
    else -> SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE
}

fun truncateSyncDetail(detail: String): String {
    if (detail.length <= MAX_SYNC_DETAIL_LENGTH) return detail
    return detail.take(MAX_SYNC_DETAIL_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
}

fun SyncIssueEntity.kindValue(): SyncIssueKind =
    runCatching { SyncIssueKind.valueOf(kind) }.getOrDefault(SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE)

fun buildRedactedSyncDiagnostics(issue: SyncIssueEntity, serverUrl: String): String = buildString {
    appendLine("server_url=$serverUrl")
    appendLine("issue_kind=${issue.kind}")
    appendLine("http_status=${issue.httpStatus ?: "none"}")
    appendLine("detected_at=${issue.detectedAt}")
    appendLine("request_checkpoint=${issue.requestCheckpoint}")
    appendLine("server_checkpoint=${issue.serverCheckpoint ?: "none"}")
    appendLine("entity_uuid=${issue.entityUuid ?: "none"}")
    appendLine("related_uuid=${issue.relatedUuid ?: "none"}")
    append("body_sha256=${issue.detail.sha256()}")
}

private fun String.validUuidOrNull(): String? = runCatching { UUID.fromString(this).toString() }
    .getOrNull()
    ?.takeIf { it.equals(this, ignoreCase = true) }

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

const val MAX_SYNC_DETAIL_LENGTH = 16_000
const val TRUNCATION_MARKER = "\n… [truncated]"
