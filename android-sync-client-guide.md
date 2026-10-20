# Android sync setup

Sync is optional; the app works locally without it. Host the [Couch Tasks server](https://github.com/burnskp/couch-tasks) yourself, or obtain credentials from its operator. Follow the server's [hosting and certificate guide](https://github.com/burnskp/couch-tasks/blob/main/docs/sync.md#run-a-server), including its NixOS setup link if applicable.

All clients accepted by a server share the same tasks. Use separate servers for separate datasets. The Android app uploads the full local snapshot, including notes, completed tasks, and deletion records.

## Prepare certificates

Obtain the final HTTPS server URL, a device-specific client certificate and private key signed by the server's trusted client CA, and the CA certificate used to verify the server. The server certificate must cover the hostname or IP in the URL. Do not copy the server's private key or the CA private key to the phone.

The app requires a password-protected PKCS#12 client bundle. On a trusted machine with OpenSSL, package your client certificate and key:

```sh
openssl pkcs12 -export -in client.crt -inkey client.key -out client.p12 -name couch-tasks-android
```

Enter a nonempty export password when prompted. If the operator supplies an intermediate client certificate chain, include it using `-certfile client-chain.pem`. Keep the resulting bundle and its password private. Transfer the bundle and server CA certificate securely to the device; do not commit them to either repository. Keep the imported files available to the app after selection.

## Configure Android

1. Open Settings → Switch server and enter the final HTTPS destination URL. Do not include URL credentials, query parameters, or a fragment.
2. Select destination-specific client `.p12` and CA certificates, then enter the bundle password. This form leaves active credentials unchanged.
3. Choose **Reset local data** to fetch the destination without uploading your local dataset, or **Preview migration** to keep local tasks by copying them to the destination. This flow also applies to first-time setup with existing local tasks.
4. For migration, review the destination URL, projects added, tasks copied, and tasks skipped. **Confirm upload to destination** sends task contents there. Cancel by choosing **Abandon**. Saving the same canonical URL makes no change.
5. After activation, use Health check and Sync now. Confirm a test task reaches another client before relying on synchronization.

### Migration matching and ordering

Projects match by the existing project-name normalization. Task titles match within their project using `trim().lowercase(Locale.ROOT)`; notes and completion status do not participate. Destination tasks and project metadata win, including existing destination duplicates. Skipped tasks’ differing notes or status are not merged. Local duplicate titles choose the earliest creation timestamp, then UUID. Tasks with the same title in different projects remain separate.

Migration copies pending and completed tasks, their notes and timestamps, and empty projects. Copies receive fresh UUIDs, revision zero, and dirty flags; matching projects reuse destination UUIDs. Pending copies append after destination pending tasks in local order; completed tasks have sort order zero. No source checkpoints, revisions, tombstones, or sync issues are carried over. The outgoing protocol snapshot includes clean destination records and dirty copies. Concurrent clients may create matching tasks after the preview; snapshot deduplication cannot prevent that race.

### Interrupted switches

The original local dataset and active profile stay intact until a successful, validated destination response is committed. A private staging database and persistent journal hold the destination and exact migration request. While a switch is pending, local edits, share imports, queued workers, and manual sync are blocked. Existing network calls are cancelled before staging; response application finishes before switching starts.

After interruption or restart, Settings offers **Resume** or **Abandon**. Resume retries the persisted request and UUIDs after an uncertain upload; it never generates new copies. An upload needs explicit confirmation at the preview. Abandon retains the original local dataset and credentials, but an uncertain upload may already have changed the destination. Neither switching nor abandonment deletes source server data. Completed/abandoned staging and inactive credentials are cleaned up.

### Transport and shared content

Production requests never follow HTTP or HTTPS redirects. Configure the final URL; hostname verification and the selected CA trust restriction remain enabled. Limits apply to decompressed response bytes: 16 MiB for successful sync, 16 KiB for HTTP error details (truncated with a marker), and 4 KiB for health checks. Oversized successful responses are rejected without changing local data or checkpoints. Calls have a 60-second overall timeout and 10-second connect/read/write timeouts. Cancellation cancels the underlying HTTP call.

Sharing text to Couch Tasks opens an editable title/notes draft. Nothing is imported until **Add**; **Cancel** creates nothing. Draft identity survives recreation to prevent duplicate submission. During a switch the draft remains available, with Add temporarily disabled.

For connectivity checks from a computer, the server guide provides an authenticated `/healthz` request. For an emulator or phone, use a server address reachable from that device and covered by its certificate; `localhost` refers to the device itself.

## Troubleshooting

Check certificate expiry, hostname coverage, CA selection, and the bundle password for setup failures. Confirm the server port is reachable. A certificate password error differs from the server rejecting a certificate or hostname.

For HTTP errors and conflicts, use the app's sync issue screen and the [manual intervention guide](android-sync-manual-intervention.md). Preserve local data before repairs. Shared diagnostics include the configured server URL and identifiers; inspect them before posting publicly.

Android backup/device transfer does not preserve this app's database or settings. Sync is not historical backup: edits and deletions propagate between clients.
