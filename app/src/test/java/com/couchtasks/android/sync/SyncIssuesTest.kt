package com.couchtasks.android.sync

import com.couchtasks.android.data.SyncIssueEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIssuesTest {
    private val project = "11111111-1111-4111-8111-111111111111"
    private val otherProject = "22222222-2222-4222-8222-222222222222"
    private val task = "33333333-3333-4333-8333-333333333333"

    @Test
    fun checkpointAhead_extractsServerCheckpointOnlyFromExactCurrentShape() {
        val classified = classifyConflict(
            "client checkpoint 12 is ahead of server checkpoint 9; sync another current client or rebuild the server",
            requestCheckpoint = 12,
        )
        assertEquals(SyncIssueKind.CHECKPOINT_AHEAD, classified.kind)
        assertEquals(9L, classified.serverCheckpoint)

        assertEquals(
            SyncIssueKind.UNKNOWN_CONFLICT,
            classifyConflict(
                "client checkpoint 11 is ahead of server checkpoint 9; sync another current client or rebuild the server",
                requestCheckpoint = 12,
            ).kind,
        )
    }

    @Test
    fun collisionAndOrphan_extractOnlyValidConsistentUuids() {
        val collision = classifyConflict(
            "project $project cannot be renamed to normalized name \"home\" because it belongs to project $otherProject; choose a distinct project name",
        )
        assertEquals(SyncIssueKind.PROJECT_NAME_COLLISION, collision.kind)
        assertEquals(project, collision.entityUuid)
        assertEquals(otherProject, collision.relatedUuid)

        val orphan = classifyConflict(
            "task $task references missing project $project; repair it with `ct update $task --project <valid-project>` or delete it with `ct delete $task`",
        )
        assertEquals(SyncIssueKind.ORPHAN_TASK, orphan.kind)
        assertEquals(task, orphan.entityUuid)
        assertEquals(project, orphan.relatedUuid)

        val inconsistent = "task $task references missing project $project; repair it with `ct update $otherProject --project <valid-project>` or delete it with `ct delete $task`"
        assertEquals(SyncIssueKind.UNKNOWN_CONFLICT, classifyConflict(inconsistent).kind)
    }

    @Test
    fun detailCapIncludesMarkerWithinLimit() {
        val truncated = truncateSyncDetail("x".repeat(MAX_SYNC_DETAIL_LENGTH + 100))
        assertEquals(MAX_SYNC_DETAIL_LENGTH, truncated.length)
        assertTrue(truncated.endsWith(TRUNCATION_MARKER))
    }

    @Test
    fun diagnosticsContainFingerprintButNotRawDetail() {
        val issue = SyncIssueEntity(
            kind = SyncIssueKind.ORPHAN_TASK.name,
            httpStatus = 409,
            detail = "secret server text",
            requestCheckpoint = 7,
            entityUuid = task,
            relatedUuid = project,
            detectedAt = "2026-09-04T10:00:00Z",
        )
        val diagnostics = buildRedactedSyncDiagnostics(issue, "https://tasks.example.com")
        assertFalse(diagnostics.contains("secret server text"))
        assertTrue(diagnostics.contains("body_sha256="))
        assertTrue(diagnostics.contains("entity_uuid=$task"))
        assertNull(issue.serverCheckpoint)
    }

    @Test
    fun rejectedStatusClassificationIsDistinct() {
        assertEquals(SyncIssueKind.INVALID_SNAPSHOT, classifyRejectedRequest(400))
        assertEquals(SyncIssueKind.PAYLOAD_TOO_LARGE, classifyRejectedRequest(413))
        assertEquals(SyncIssueKind.REQUEST_CONFIGURATION, classifyRejectedRequest(415))
        assertEquals(SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE, classifyRejectedRequest(422))
        assertEquals(SyncIssueKind.CLIENT_SERVER_INCOMPATIBLE, classifyRejectedRequest(401))
    }
}
