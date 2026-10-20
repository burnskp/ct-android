# Android Sync Manual Intervention Guide

This document is an implementation handoff for adding actionable sync-error
handling to the Couch Tasks Android app. It supplements the
[Android sync client guide](android-sync-client-guide.md).

The important rule is that a failed `POST /sync` has not partially succeeded.
The server applies a request in one transaction and rolls the entire request
back before returning an error. On every non-`200` response, the Android app
must keep its checkpoint, rows, tombstones, revisions, and dirty flags
unchanged.

## Required app behavior

The app should distinguish three outcomes:

1. **Applied**: HTTP `200`; apply the complete response in one local
   transaction and then save its checkpoint.
2. **Needs intervention**: a non-retryable HTTP response such as `400`, `409`,
   `413`, `415`, or another `4xx`; persist a sync issue, stop scheduled retries,
   and show an actionable UI.
3. **Retry later**: a timeout, connection failure, or `5xx`; keep local state
   unchanged and allow normal bounded/background retry.

Do not implement `409` as last-write-wins, reset the checkpoint, clear dirty
flags, delete the local database, or retry continuously. Those actions can hide
the problem or lose the only complete copy of the user's data.

HTTP error bodies are plain text, not JSON. Always retain and display the body
as diagnostic detail. The current text is useful to people, but it is not a
versioned machine-readable API, so targeted parsing must have a generic
fallback.

## HTTP response classification

| Result | Meaning | Android action |
| --- | --- | --- |
| `200 OK` | The server committed the request and returned a `SyncResponse`. | Apply transactionally. Do not report an error merely because the response contains tombstones. |
| `400 Bad Request` | The decoded snapshot violates a server invariant, or the JSON is malformed. | Block retries. Run local validation and direct the user to repair the identified data; treat an unrecognized message as an app/data-integrity issue. |
| `409 Conflict` | The snapshot is valid but cannot be reconciled without a user or operator choice. | Block retries and use the conflict flows below. |
| `413 Payload Too Large` | The HTTP JSON extractor refused the request body size. | Block retries. Preserve all data and report that the app/server currently needs a payload-size solution; the protocol has no pagination or safe tombstone pruning. |
| `415 Unsupported Media Type` | The request is missing `Content-Type: application/json` or uses the wrong type. | Block retries and report an app build/configuration defect. |
| Other `4xx` | This includes JSON shape/type rejection statuses produced by the HTTP framework. | Block retries, preserve the body, and report a client/server compatibility or request defect. |
| `5xx` | Server storage or processing failure. | Retry later with backoff. If it persists, expose the body to the operator. |
| TLS failure | The request never reached the endpoint. | Prompt for server URL, CA, client certificate, expiry, and hostname/SAN checks. Do not describe it as a password failure. |

The Android request must send both:

```text
Content-Type: application/json
Accept: application/json
```

## `409 Conflict` flows

All `409` responses roll back the complete server request. Unrelated dirty
changes remain local and will upload after the blocking issue is repaired.

### Client checkpoint is ahead of the server

Current server message:

```text
client checkpoint CLIENT is ahead of server checkpoint SERVER; sync another current client or rebuild the server
```

This means the server has lost or rolled back sync history that this device has
already applied.

Show a blocking issue such as:

> The server is behind this device's sync history. Your local tasks are safe,
> but sync cannot continue until the server is repaired.

Offer these actions:

- **Retry after repair**: useful after another client or the operator has
  restored the server to a current revision.
- **Copy diagnostics**: include the server URL and both checkpoint numbers, but
  exclude task titles, notes, certificates, and private keys.
- **Open setup/help**: explain that the operator may sync a suitable current
  client or rebuild the server database. A pristine rebuilt server accepts a
  complete client snapshot as a bootstrap.

The app must not silently lower its checkpoint to the server value or to zero.
Before an operator rebuilds a server, they should back up the server database
and confirm that a client contains the complete desired task set.

### Project rename collides with another project

Current server message:

```text
project PROJECT_UUID cannot be renamed to normalized name "NAME" because it belongs to project OTHER_PROJECT_UUID; choose a distinct project name
```

Open the project identified by `PROJECT_UUID` and require a name that does not
normalize to any other local project name. Normalization must match the server:
trim surrounding whitespace and lowercase using locale-independent Unicode
behavior (`lowercase(Locale.ROOT)` on Android).

Recommended UI:

- explain that another project already uses the requested name;
- show both projects when they can be resolved locally;
- offer **Rename project** and **Cancel**;
- mark the renamed project dirty and retry only after the edit commits.

If message parsing fails or either UUID is not available locally, show the raw
server text and a generic project-management action. Do not guess which project
to merge or delete.

### Task references a missing project

Current server message:

```text
task TASK_UUID references missing project PROJECT_UUID; repair it with `ct update TASK_UUID --project <valid-project>` or delete it with `ct delete TASK_UUID`
```

The CLI commands in the response describe the required data change. Android
should translate them into native actions:

- **Move task**: let the user select an existing, non-deleted project. Update
  `project_uuid`, assign a valid pending `sort_order` if needed, update
  `updated_at`, and set `sync_dirty = true` while retaining the last known
  `sync_revision`.
- **Delete task**: insert a dirty task tombstone, remove the task, and compact
  pending order in its old project.
- **Cancel**: retain the issue and keep sync blocked.

Use `TASK_UUID` to locate the task; show its title in the UI only after reading
it from the local database. Do not include the title or notes in logs or copied
diagnostics.

### Unknown conflict

For any other `409`, preserve the status and raw body, show a generic “Sync
needs attention” issue, and allow the user to retry or copy redacted
diagnostics. Do not infer a destructive repair from text that the app does not
recognize.

## `400` snapshot validation failures

Validate before making the network request. This avoids a permanently failing
WorkManager job and lets the app attach repair UI to local entities.

The current server rejects these conditions:

- a negative checkpoint;
- a negative project, task, or tombstone revision;
- an empty or incorrectly normalized project name;
- duplicate project names after normalization;
- duplicate project/task entity keys, including a row and tombstone for the
  same entity;
- a task status other than exactly `pending` or `done`;
- a pending task whose `sort_order` is outside `1..pendingTaskCount` for its
  project.

The client should also verify that every task refers to a project included in
the full local project snapshot. A missing reference otherwise becomes the
actionable `409` described above.

Safe repairs that may run locally in one transaction are:

- recompute `name_normalized` from the trimmed display name;
- compact pending task positions to a contiguous one-based sequence using the
  app's stable visible order.

Do not automatically resolve duplicate project names, unknown task statuses,
negative revisions/checkpoints, or row/tombstone identity collisions. Those
conditions indicate ambiguous data or an app/database defect. Preserve the
database, block sync, offer redacted diagnostics/export, and require an explicit
repair or an app update.

## Successful responses that still resolve a conflict

Some concurrent edits resolve through a normal `200` response. They do not
block sync and must not be converted into HTTP-style intervention errors.

Apply returned data in this exact order inside one transaction:

1. projects;
2. tasks;
3. tombstones;
4. checkpoint.

In particular:

- a stale edit to a deleted task receives a task tombstone;
- a stale task created under a deleted project may receive only the project
  tombstone;
- moving an existing task into a project deleted by another client receives the
  project tombstone and a task tombstone;
- a project tombstone must delete all local tasks that reference that project;
- tombstone application must be idempotent.

If a returned tombstone will delete a locally dirty task, the app may create a
local recovery record before applying the deletion. After sync commits, it can
notify the user that another client deleted the task or its project and offer
**Restore as new task**. Restoration must generate a new task UUID and place it
in an existing project; tombstoned UUIDs must never be reused.

Recovery records are local UI history. They must not prevent applying the
server response or advancing the checkpoint.

## Suggested Android result model

Do not reduce every failure to `IOException`. A typed result prevents
WorkManager from retrying permanent conflicts indefinitely.

```kotlin
sealed interface SyncResult {
    data class Applied(val checkpoint: Long) : SyncResult

    data class NeedsIntervention(
        val kind: Kind,
        val httpStatus: Int?,
        val detail: String,
    ) : SyncResult

    data class RetryLater(
        val detail: String,
    ) : SyncResult
}

enum class Kind {
    CHECKPOINT_AHEAD,
    PROJECT_NAME_COLLISION,
    ORPHAN_TASK,
    INVALID_SNAPSHOT,
    PAYLOAD_TOO_LARGE,
    REQUEST_CONFIGURATION,
    CLIENT_SERVER_INCOMPATIBLE,
    UNKNOWN_CONFLICT,
}
```

Read an error body once and classify the response before decoding success JSON:

```kotlin
httpClient.newCall(httpRequest).execute().use { response ->
    val responseText = response.body?.string().orEmpty()

    return when {
        response.code == 200 -> {
            val payload = json.decodeFromString<SyncResponse>(responseText)
            applyResponseTransactionally(payload)
            SyncResult.Applied(payload.checkpoint)
        }

        response.code == 409 -> {
            persistIntervention(classifyConflict(responseText), 409, responseText)
        }

        response.code in setOf(400, 413, 415, 422) -> {
            persistIntervention(classifyRejectedRequest(response.code), response.code, responseText)
        }

        response.code >= 500 -> SyncResult.RetryLater(
            detail = "Server returned HTTP ${response.code}",
        )

        else -> persistIntervention(
            Kind.CLIENT_SERVER_INCOMPATIBLE,
            response.code,
            responseText,
        )
    }
}
```

Conflict text recognition should be conservative. Match the stable leading
phrases (`client checkpoint`, `project`, or `task`) and expected UUIDs, then fall
back to `UNKNOWN_CONFLICT`. Always retain the original body for display because
server wording may evolve.

## Persisting intervention state

A small singleton or history table makes the blocked state survive process
death:

| Field | Purpose |
| --- | --- |
| `kind` | One of the typed intervention kinds above. |
| `http_status` | Nullable for local preflight/TLS issues. |
| `server_detail` | Plain-text response, size-limited for display. |
| `request_checkpoint` | Checkpoint used by the failed request. |
| `entity_uuid` | Optional offending project/task UUID. |
| `detected_at` | UTC timestamp. |
| `request_fingerprint` | Optional hash of non-secret request identity/revisions to suppress duplicate notices. |

Do not persist a second copy of the full sync request in this table: it contains
task titles and notes. The authoritative rows already remain in Room because a
failed sync does not mutate them.

Clear an issue only when:

- the relevant local repair is committed and a later sync succeeds;
- an operator repair is confirmed by a successful sync; or
- the user explicitly dismisses an informational recovery notice.

Dismissing a still-blocking error must not clear dirty flags or mark sync as
healthy.

## WorkManager and UI behavior

- Return `Result.failure()` (or an app-specific blocked state) for a persisted
  intervention; do not return `Result.retry()`.
- Return `Result.retry()` only for transient transport and `5xx` failures, with
  backoff.
- Show a persistent sync-status entry point while blocked. Avoid a modal dialog
  on every app launch.
- Manual **Retry** should re-run local validation and create a fresh request from
  the current database, never replay cached JSON.
- Continue allowing local edits while blocked. Re-export the complete current
  snapshot after repair.
- Serialize response application with local writes as described in the main
  Android sync guide.

## Acceptance tests for the Android update

1. A `409` does not change the checkpoint, rows, tombstones, revisions, or dirty
   flags and does not schedule automatic retries.
2. A checkpoint-ahead response produces an operator-facing issue and never
   resets the local checkpoint.
3. A project-name collision opens the correct project rename flow; after a
   unique rename, the next request succeeds.
4. An orphan-task conflict offers move and delete. Each action produces a valid
   full snapshot and unblocks sync after success.
5. An unknown `409` shows the raw body and performs no destructive repair.
6. `400`, `413`, `415`, and JSON shape/type `4xx` responses remain distinct in
   telemetry and UI classification.
7. Timeouts and `5xx` retry with backoff; permanent `4xx` responses do not.
8. A `200` containing a project tombstone deletes all child tasks and advances
   the checkpoint atomically.
9. A `200` containing project and task tombstones for a stale move is
   idempotent and leaves no local copy of the task.
10. If a tombstone removes a dirty local task, an optional recovery action
    recreates it with a new UUID under a valid project.
11. No diagnostic log or copied report contains task titles, notes, client
    private keys, or certificate contents.

