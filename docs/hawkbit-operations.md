# hawkBit operations

The Home-only endpoint is `https://ota.calcifer.tech`. LAN DNS resolves this
name to `192.168.0.102`. Traefik's Service uses `externalTrafficPolicy: Local`
to preserve client addresses, and the hawkBit middleware accepts only
`192.168.0.0/24`. The operator reports that the endpoint is not reachable from
the Internet; router and firewall forwarding/SNAT rules have not been
independently inspected. The data volume is not encrypted.

The current image provenance and Eclipse migration assessment are recorded in
[hawkBit migration assessment](hawkbit-migration-assessment.md). No candidate
image has been migrated against a copy of the persistent data.

cert-manager renews the `hawkbit-tls` secret. Traefik reads certificate updates
from Kubernetes; monitor certificate expiry and verify the served certificate:

```sh
curl --silent --show-error --output /dev/null --write-out 'HTTP %{http_code}; TLS verify %{ssl_verify_result}\n' https://ota.calcifer.tech/rest/v1/targets
```

An unauthenticated request should return HTTP 401 and TLS verification 0.

The registered controller ID is `therment-01`. Its dedicated target token is
stored in the SOPS-encrypted `therment-01-ota-credentials.sops.yaml` alongside
the server manifests, but is not deployed as a Kubernetes Secret. A trusted
build or provisioning process with the project's age identity can decrypt the
`stringData.target-token` field directly into its own memory or a protected
pipe. Never print the token or pass it as a command-line argument. The token has
been provisioned in the OTA-enabled firmware; successful physical updates in
actions 11 and 12 confirmed that the device could authenticate and download a
release. Keep the firmware and generated build files protected as credentials.

## Backup and restore

The retained PVC protects against automatic volume deletion, not data loss.
Backups contain the database, target tokens and firmware binaries: keep them on
a trusted storage location, restrict access to the operator, and do not commit
or upload them to an untrusted service. The PVC and backups are not encrypted at
rest. Backups are stored in the retained `hawkbit-backups` PVC on the Home
node. It shares the underlying disk with the active PVC, so it does not protect
against node or disk loss; the PVC size is not a guaranteed filesystem quota.
No scheduled backup is currently configured. Each successful manual backup
retains the newest archive for seven days and four ISO weeks that have backups;
older matching archives are deleted after the new archive and checksum have
been verified. Monitor filesystem space and run backups on an agreed schedule.

To create a backup from a trusted workstation with `kubectl` and `flux` access:

```sh
python3 scripts/hawkbit-local-backup.py
```

The operation briefly stops hawkBit and resumes Flux. If the process or machine
is forcibly interrupted, verify `deployment/hawkbit` has one desired replica
and `home-apps` is not suspended, then restore them if necessary.

For a rehearsal or other backup that must preserve every existing archive, run
`python3 scripts/hawkbit-local-backup.py --no-retention`. The new archive is
still checksum-verified, but no older matching archives are removed.

For a consistent backup, suspend the `home-apps` Flux Kustomization, scale the
hawkBit Deployment to zero, and wait until its pod has terminated. Mount the
`hawkbit-data` PVC at `/data` in a temporary helper pod running as UID/GID 1001
with fsGroup 1001. Stream `tar cf - -C /data .` into a file created with mode
0600 on trusted storage; the hawkBit image has `tar` but does not include
`gzip`. Delete the helper pod, scale the Deployment back to one, resume Flux,
and verify the rollout and authenticated device access. Always resume Flux and
restore the Deployment even if the backup fails; do not archive a live H2
database or expose the archive in terminal output.

To rehearse restoration, create a new empty PVC, mount it in a helper pod with
the same UID, GID and fsGroup, and stream the archive to
`tar xf - --strip-components=1 -C /data`. The strip option avoids trying to
change ownership or permissions of the mounted volume root. Start an isolated
hawkBit instance against the new PVC, with no public Service or ingress, and
verify the restored target and artifact contents before removing the test PVC.
For production recovery, stop the application first and restore to a separate
empty PVC; do not overwrite the only existing volume. Retain the original PVC
until the restored service has been verified.

On 2026-11-16 an isolated restore from a stopped production PVC successfully
started hawkBit and authenticated `therment-01` with its existing token. A
synthetic, unassigned artifact uploaded by the publisher was downloaded from the
restored instance and matched its original SHA-256. The temporary module,
archive and test PVC were removed. The archive was kept in RAM. Recovery of an
actual firmware release has not yet been tested.
