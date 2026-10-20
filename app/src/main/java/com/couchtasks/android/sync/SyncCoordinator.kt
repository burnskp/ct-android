package com.couchtasks.android.sync

import com.couchtasks.android.data.CouchTasksDatabase
import com.couchtasks.android.data.SyncIssueEntity
import com.couchtasks.android.data.WriteSyncMutex
import com.couchtasks.android.data.utcNowRfc3339
import kotlinx.coroutines.sync.withLock

sealed interface SyncRunResult {
    sealed interface Applied : SyncRunResult
    sealed interface RetryLater : SyncRunResult
    sealed interface NeedsIntervention : SyncRunResult {
        val kind: SyncIssueKind
        val httpStatus: Int?
        val detail: String
    }

    data class Success(val response: SyncResponse) : Applied
    data class RetryableFailure(val transportResult: SyncTransportResult) : RetryLater
    data class CertificateConfigurationFailure(
        val transportResult: SyncTransportResult.CertificateConfigurationError,
        override val detail: String = transportResult.cause.message.orEmpty(),
    ) : NeedsIntervention {
        override val kind = SyncIssueKind.TLS_CONFIGURATION
        override val httpStatus: Int? = null
    }
    data class ClientProtocolMismatch(
        val transportResult: SyncTransportResult.Http4xxMismatch,
        override val kind: SyncIssueKind = classifyRejectedRequest(transportResult.code),
        override val detail: String = transportResult.body,
    ) : NeedsIntervention {
        override val httpStatus: Int = transportResult.code
    }
    data class MalformedJsonFailure(
        val transportResult: SyncTransportResult.MalformedJson,
        override val detail: String = transportResult.body,
    ) : NeedsIntervention {
        override val kind = SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE
        override val httpStatus: Int = 200
    }
    data object ResponseTooLarge : NeedsIntervention {
        override val kind = SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE
        override val httpStatus: Int = 200
        override val detail = "Sync response exceeds 16 MiB; no data applied"
    }
    data object SwitchInProgress : RetryLater
    data object LocalStateChangedDuringSync : RetryLater
    data class RolledBackInvalidResponse(
        val cause: InvalidSyncResponseException,
        override val detail: String = cause.message.orEmpty(),
    ) : NeedsIntervention {
        override val kind = SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE
        override val httpStatus: Int = 200
    }
    data class LocalSnapshotInvalid(val issue: SyncIssueEntity) : NeedsIntervention {
        override val kind = SyncIssueKind.INVALID_SNAPSHOT
        override val httpStatus: Int? = null
        override val detail: String = issue.detail
    }
    data class Failed(
        val transportResult: SyncTransportResult,
        override val detail: String = (transportResult as? SyncTransportResult.HttpProtocolError)?.body.orEmpty(),
    ) : NeedsIntervention {
        override val kind = SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE
        override val httpStatus: Int? = (transportResult as? SyncTransportResult.HttpProtocolError)?.code
    }
}

private object SyncRunMutex {
    val mutex = kotlinx.coroutines.sync.Mutex()
}

class SyncCoordinator(
    private val db: CouchTasksDatabase,
    private val transport: SyncTransport,
    private val onIntervention: suspend () -> Unit = {},
    private val now: () -> String = ::utcNowRfc3339,
) {
    private val validator = SyncPreflightValidator(db, now)
    private val exporter = SyncExporter(db)
    private val applier = SyncResponseApplier(db)

    suspend fun syncOnce(baseUrl: String): SyncRunResult = SyncRunMutex.mutex.withLock sync@{
        val preflight = WriteSyncMutex.mutex.withLock {
            if (db.connectionDao().journal() != null || ConnectionOperations.stopping) return@sync SyncRunResult.SwitchInProgress
            validator.validateAndExport()
        }
        if (preflight is SyncPreflightResult.Invalid) {
            onIntervention()
            return@sync SyncRunResult.LocalSnapshotInvalid(preflight.issue)
        }
        val request = (preflight as SyncPreflightResult.Valid).request
        when (val result = transport.sync(baseUrl, request)) {
            is SyncTransportResult.Success -> WriteSyncMutex.mutex.withLock {
                if (db.connectionDao().journal() != null || ConnectionOperations.stopping) return@withLock SyncRunResult.SwitchInProgress
                if (exporter.exportRequest() != request) {
                    SyncRunResult.LocalStateChangedDuringSync
                } else {
                    when (val applied = applySuccessfulResponse(result.response)) {
                        is SyncRunResult.NeedsIntervention -> intervention(request, applied)
                        else -> applied
                    }
                }
            }
            is SyncTransportResult.NetworkRetryableFailure,
            is SyncTransportResult.Http5xxFailure -> SyncRunResult.RetryableFailure(result)
            is SyncTransportResult.CertificateConfigurationError -> intervention(
                request,
                SyncRunResult.CertificateConfigurationFailure(result),
            )
            is SyncTransportResult.Http4xxMismatch -> {
                val classified = if (result.code == 409) classifyConflict(result.body, request.checkpoint) else {
                    ClassifiedSyncIssue(classifyRejectedRequest(result.code))
                }
                intervention(request, SyncRunResult.ClientProtocolMismatch(result, classified.kind), classified)
            }
            SyncTransportResult.ResponseTooLarge -> intervention(request, SyncRunResult.ResponseTooLarge)
            is SyncTransportResult.MalformedJson -> intervention(request, SyncRunResult.MalformedJsonFailure(result))
            else -> intervention(request, SyncRunResult.Failed(result))
        }
    }

    private suspend fun intervention(
        request: SyncRequest,
        result: SyncRunResult.NeedsIntervention,
        classified: ClassifiedSyncIssue = ClassifiedSyncIssue(result.kind),
    ): SyncRunResult {
        db.syncIssueDao().upsert(
            SyncIssueEntity(
                kind = classified.kind.name,
                httpStatus = result.httpStatus,
                detail = truncateSyncDetail(result.detail),
                requestCheckpoint = request.checkpoint,
                entityUuid = classified.entityUuid,
                relatedUuid = classified.relatedUuid,
                serverCheckpoint = classified.serverCheckpoint,
                detectedAt = now(),
            ),
        )
        onIntervention()
        return result
    }

    private suspend fun applySuccessfulResponse(response: SyncResponse): SyncRunResult = try {
        applier.apply(response)
        SyncRunResult.Success(response)
    } catch (error: InvalidSyncResponseException) {
        SyncRunResult.RolledBackInvalidResponse(error)
    } catch (error: IllegalArgumentException) {
        SyncRunResult.RolledBackInvalidResponse(
            InvalidSyncResponseException(error.message ?: "Invalid sync response"),
        )
    }
}
