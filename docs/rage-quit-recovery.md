# Rage Quit offline recovery

## Preconditions and trust boundary

These are operator-run procedures, **not** deployment automation. Obtain specific
authorization for stopping production, reading private records, off-node transfer,
restore/replacement and compatible image rollback. Use a private maintenance
environment, no terminal logging/debug tracing, no production files in Git or
public HTTP paths. Backup identities must be outside Git in 0600 files. Pass
file paths, never key/passphrase/token values through arguments or environment
dumps. Retention must be operator-controlled, access-restricted and encrypted
off-node; another directory or volume on the same VPS is not disaster recovery.

Use JDK 25 and the SQLite driver extracted from the tested app jar. The scripts
extract only its approved SQLite and SnakeYAML jars into 0700 scratch space;
they do not install a Python/DB/backup dependency. Required local tooling is
Bash, coreutils, `flock`, `age`/`age-keygen` and JDK `java`/`jar`. The same release's
`V1.sql` must be available. Helpers support **only schema version 1** and reject
other versions, schema/constraint differences, integrity/foreign-key failures,
non-DELETE journals and a different persisted start date. Runtime durability
continues to be `DELETE`/`FULL`; foreign keys and busy timeout are connection
settings reapplied by the application, not stored in the backup.

## Stop all writers before any file copy

1. Verify the actual kube context is Calcifer Cloud and the claim is
   `web/rage-quit-data`. Record the current immutable digest and agreed start
   date privately; do not capture tracking data in incident comments.
2. Suspend the **dedicated rage-quit** Flux Kustomization before scaling the
   Deployment to zero, so reconciliation cannot recreate the writer. Do not
   suspend `cloud-apps`, the root reconciler or auth. Suspend alone does not stop
   running pods. Scale down cleanly and wait until every app pod is terminated.
3. Mount the existing preserved RWO PVC in one temporary maintenance pod pinned
   to the claim's node. Use the tested release, UID/GID 10001, a bounded executable
   tmp mount, no service-account token, no public Service/Ingress and no second
   app process. A separate reviewed JDK/tooling maintenance environment can run
   these helpers against the mounted storage; do not start the JVM app launcher.
4. Check there is no writer or overlapping maintenance job. The explicit
   `--offline-writer-stopped` acknowledgement is a human prerequisite, **not**
   a Kubernetes check. `flock` prevents competing cooperating helper operations
   only; the application does not take that advisory lock.
5. Refuse to copy while any `-journal`, `-wal` or `-shm` sidecar exists. Never
   delete a journal to bypass this gate. If shutdown was interrupted, have the
   operator authorize recovery with the compatible application, let SQLite
   recover, stop it cleanly again, and repeat all gates. No live-file `cp`.

## Consistent encrypted backup

Place the public age recipients in a protected file. Keep its corresponding
private identity separately (ideally offline) and verify recovery access before
accepting a backup. Select explicit absolute paths outside the repository. The
encrypted output must not exist; the script will not overwrite it.

<augment_code_snippet mode="EXCERPT">
````sh
bash scripts/rage-quit-backup.sh /data/rage-quit.sqlite \
  /private/snapshots/selected.age /private/backup-recipients \
  "$agreed_start_date" --offline-writer-stopped
````
</augment_code_snippet>

The helper validates the stopped source, copies into restricted temporary space,
validates the copy and compares all settings/events/declarations/deduplication
rows before encrypting. It publishes encrypted output atomically without
clobbering an existing snapshot. It logs only success/failure, never SQL/rows or
derived fingerprints. Private transient plaintext copies and extracted libraries
are removed at exit; these are disposable copies, not the only recovery state.

Transfer **only the encrypted snapshot** to the approved protected off-node
destination with existing authenticated tooling/session files (no credential
arguments). Confirm transfer integrity and availability without printing secrets
or database contents. Set an explicit retention schedule, keep multiple tested
generations including pre-upgrade snapshots, and restrict delete permissions.
Document the date/release/schema, snapshot identifier and verification result
privately, without activity records. Rehearse decryption/restore on disposable
storage at the destination. Do not call a same-node snapshot a completed backup.
Remove the maintenance pod before restarting the sole app writer, then resume
only its dedicated Flux reconciliation after acceptance.

## Guarded restore

Stop writers as above. Verify authorization and select the trusted encrypted
snapshot, corresponding private identity and expected shared start date. Stage
on disposable storage first. The destination DB must already exist: the helper
never silently initializes empty production state. Use a new explicit retention
directory on protected storage with adequate free space; it must survive the
maintenance pod and must not be a temporary job filesystem.

<augment_code_snippet mode="EXCERPT">
````sh
bash scripts/rage-quit-restore.sh /private/selected.age /data/rage-quit.sqlite \
  /private/backup-identity "$agreed_start_date" /data/pre-restore-selected \
  --offline-writer-stopped --replace-current
````
</augment_code_snippet>

Decryption occurs in 0700 staging beside the DB. All candidate gates run **before**
any overwrite or retention creation. An invalid/decryption-failed/wrong-date
candidate leaves current state untouched. The helper then retains an exact copy
of the existing DB (even if it was corrupt), verifies that copy, restores the
existing UID/GID and restrictive 0600 file/0700 retention permissions, and performs
a same-filesystem atomic replacement. Preserve the retention directory until
acceptance and another encrypted off-node snapshot; encrypt pre-restore state
before off-node retention. Never prune/delete the PVC to restore.

After replacement keep all writers stopped until the compatible release is
selected. Remove maintenance before restarting one app pod. Verify private
readiness, unchanged start date, owned event history, zero declarations,
deduplication behavior, scores, adjacent smoking intervals and daily/cumulative
charts using authorized accounts; do not print private data into logs. Sessions
are not restored and all participants must log in again. If acceptance fails,
stop again and recover the retained state under a fresh approved restore plan,
never blindly copy a file over a running writer.

## Schema-compatible image rollback

Retain the PVC and current database. Check the prior tested digest explicitly
supports current `user_version=1`, the exact tables/settings and the unchanged
configured start date; do not infer compatibility from an image tag. Take and
verify a fresh encrypted off-node snapshot first. After operator approval change
**only** the Cloud `image-patch.yaml` to that real prior digest and reconcile the
single `Recreate` deployment. Confirm persistence/probes and participant reports.
No data restore is needed for a compatible rollback; new records remain intact.

If a future migration makes the prior image incompatible, **stop**: these v1
helpers reject that schema. Use the migration owner's explicitly approved
recovery procedure and a verified compatible pre-migration snapshot while
preserving the newer current state. Explain which post-snapshot records would
be lost and obtain approval. Do not bypass schema/start-date gates, rebuild an
old tag or change the schema version to make validation pass. Auth rollback is
separate and symmetric on both edges; app rollback never changes Home resources.

## Local rehearsal

`bash scripts/rage-quit-rehearse.sh` uses only synthetic schema-v1 records, a
test-only date and a disposable age identity. It verifies encryption/restore,
all persisted report inputs, retained prior state, ownership/permissions and
rejection of invalid and mismatched-date snapshots. Exact restored event and
declaration inputs preserve deterministic scores/intervals/chart data; backend
tests validate the actual calculation algorithms. This is **not** a live OIDC,
off-node retention or production restore test. Scratch plaintext and temporary
keys are removed at exit; production identities are never used.
