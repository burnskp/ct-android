package com.couchtasks.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncModelsJsonTest {
    @Test
    fun syncRequest_matchesGuideEmptyRequestFixture() {
        val request = SyncRequest(checkpoint = 0)

        val encoded = SyncJson.encodeToString(SyncRequest.serializer(), request)
        val decoded = SyncJson.decodeFromString(SyncRequest.serializer(), GUIDE_EMPTY_REQUEST)

        assertEquals(request, decoded)
        assertEquals(
            "{\"checkpoint\":0,\"projects\":[],\"tasks\":[],\"tombstones\":[]}",
            encoded,
        )
    }

    @Test
    fun syncResponse_matchesGuideEmptyResponseFixture() {
        val response = SyncJson.decodeFromString(SyncResponse.serializer(), GUIDE_EMPTY_RESPONSE)

        assertEquals(SyncResponse(checkpoint = 42), response)
    }

    @Test
    fun syncRows_useGuideSnakeCaseFieldNames() {
        val encoded = SyncJson.encodeToString(
            SyncRequest.serializer(),
            SyncRequest(
                checkpoint = 7,
                projects = listOf(
                    SyncProject(
                        uuid = "project-1",
                        name = "Inbox",
                        nameNormalized = "inbox",
                        createdAt = "2026-04-27T10:03:00Z",
                        updatedAt = "2026-04-27T10:04:00Z",
                        archivedAt = null,
                        syncRevision = 3,
                        syncDirty = true,
                    ),
                ),
                tasks = listOf(
                    SyncTask(
                        uuid = "task-1",
                        projectUuid = "project-1",
                        sortOrder = 1,
                        title = "Read",
                        notes = "https://example.com",
                        status = "pending",
                        createdAt = "2026-04-27T10:03:00Z",
                        updatedAt = "2026-04-27T10:04:00Z",
                        completedAt = null,
                        syncRevision = 4,
                        syncDirty = true,
                    ),
                ),
                tombstones = listOf(
                    SyncTombstone(
                        entityType = "task",
                        uuid = "task-2",
                        deletedAt = "2026-04-27T10:05:00Z",
                        syncRevision = 5,
                        syncDirty = true,
                    ),
                ),
            ),
        )

        assertTrue(encoded.contains("\"name_normalized\":\"inbox\""))
        assertTrue(encoded.contains("\"archived_at\":null"))
        assertTrue(encoded.contains("\"project_uuid\":\"project-1\""))
        assertTrue(encoded.contains("\"sort_order\":1"))
        assertTrue(encoded.contains("\"completed_at\":null"))
        assertTrue(encoded.contains("\"sync_revision\":4"))
        assertTrue(encoded.contains("\"sync_dirty\":true"))
        assertTrue(encoded.contains("\"entity_type\":\"task\""))
        assertTrue(encoded.contains("\"deleted_at\":\"2026-04-27T10:05:00Z\""))
    }

    private companion object {
        const val GUIDE_EMPTY_REQUEST = """
            {
              "checkpoint": 0,
              "projects": [],
              "tasks": [],
              "tombstones": []
            }
        """

        const val GUIDE_EMPTY_RESPONSE = """
            {
              "checkpoint": 42,
              "projects": [],
              "tasks": [],
              "tombstones": []
            }
        """
    }
}
