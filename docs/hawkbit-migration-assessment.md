# hawkBit migration assessment

Assessment date: 2026-11-16. This records provenance and compatibility questions;
it is not approval to change the Home deployment.

## Current deployment

- `clusters/calcifer-home/apps/home-automation/hawkbit/deployment.yaml` uses
  `openremote/hawkbit-update-server@sha256:0a8114d12b2b7bfa33def41bfac280abc20c5675f4f871f5501b2cdcf531525d`.
  Registry inspection confirms this is the multi-arch index digest for
  `openremote/hawkbit-update-server:1.0.3.0` (amd64 and arm64 manifests).
- OpenRemote's image repository documents that its wrapper downloads the
  official Eclipse `hawkbit-update-server` JAR from Maven Central and verifies
  its GPG signature. The wrapper tag is `1.0.3.0`; the embedded application is
  hawkBit `1.0.3`. This is not the Eclipse-published container image.
- The deployment runs one replica with a `Recreate` strategy. H2 is file-backed
  at `/data/database/hawkbit` (`MODE=LEGACY`, `DB_CLOSE_ON_EXIT=FALSE`). The
  database is on `hawkbit-data` under `database`; artifacts are stored at
  `/opt/hawkbit/artifactrepo` on the same PVC under `artifacts`. The PVC is 4Gi,
  local-path, and `Retain`; it is not encrypted. A separate retained 8Gi local
  PVC holds unencrypted backups on the same node/disk.
- Runtime configuration disables DMF RabbitMQ, enables DDI target-token
  authentication, stores admin and uploader password hashes through the
  `hawkbit-credentials` Secret, and stores the device token in a separate SOPS
  file. The uploader's configured permissions cover software modules,
  distribution sets, target reads/updates, and artifact reads. No secret values
  are included here.
- The ingress is LAN-allow-listed and publishes `https://ota.calcifer.tech`;
  forwarded headers and generated artifact URLs are configured for HTTPS on
  port 443. The endpoint and device authentication/download behavior are
  described in [hawkBit operations](hawkbit-operations.md).

## Client contract to preserve

The Therment publisher uses Basic authentication on the Management REST API to
create/find software modules, upload multipart artifacts, create/find
distribution sets, associate modules, assign a set to `therment-01`, and inspect
the resulting action. The device uses DDI at
`/DEFAULT/controller/v1/therment-01` with `Authorization: TargetToken …`; it
follows the deployment link, downloads the artifact using the returned URL, and
posts DDI feedback. It requires HTTPS, the trusted hostname, no redirects, and
successful artifact size/SHA-256 verification. The read-only status tool also
reads the Management REST action and installed distribution. Any candidate must
preserve these paths, credentials, permissions, response/link shapes, and
externally generated HTTPS artifact URLs. The currently installed firmware has
successfully authenticated and downloaded releases, but that does not establish
compatibility with a different server image.

## Eclipse candidate and known differences

The authoritative application release candidate is Eclipse hawkBit `1.2.0`
([release notes](https://github.com/eclipse-hawkbit/hawkbit/releases/tag/1.2.0)).
Registry inspection on the assessment date confirmed
`hawkbit/hawkbit-update-server:1.2.0` at image-index digest
`sha256:c78f25c2815682c1e4026f504faf1189ad7acba24cdf72468941260446966411`;
its `linux/amd64` image manifest is
`sha256:7a104e6533d1369943857a572e3441d96234b848fd97cb32753b78fa827119be`.
This identifies a test candidate only; it is not yet validated against this
deployment's data or configuration.

Release notes identify material changes that require rehearsal:

- The runtime baseline moves to Java 25 (the current OpenRemote wrapper
  documents Java 21).
- DDI/DMF deployment-model configuration is stricter; the 1.2.0 notes state
  DMF startup requires `hawkbit.dmf.deployment-model=DM_SERVER_FOR_ALL`.
  Confirm the monolith's actual startup requirements with the candidate image.
- Schedulers are disabled by default and must be explicitly enabled for any
  scheduler functionality this installation depends on.
- Tenant identifiers are trimmed and upper-cased. The configured DDI tenant is
  already `DEFAULT`, but persisted tenant data must still be checked on a clone.
- Auto-assignment data moves from `sp_target_filter_query` to
  `sp_auto_assignment` through a database migration. The exact Flyway path and
  the resulting H2 schema must be exercised from the cloned 1.0.3 database.
- The release notes require hawkBit 0.10.0 as a migration baseline for older
  installations moving to 1.0.0. This deployment runs 1.0.3, but its stored
  migration history must be verified from the isolated copy before assuming the
  baseline is satisfied.

The database and artifact repository are separate parts of the same PVC and
must be copied and restored together. A successful schema migration must not be
treated as a reversible image rollback: preserve the untouched original copy
and prove recovery by restoring it to a fresh PVC with the old image.

## Rehearsal evidence and gate

The 2026-11-16 exercise documented in
[hawkBit operations](hawkbit-operations.md#backup-and-restore) restored an
isolated copy, started the current hawkBit image, authenticated `therment-01`,
and downloaded a synthetic artifact whose SHA-256 matched. It was a restore and
client-path check, not a migration to Eclipse 1.2.0. The temporary archive and
PVC were removed; actual firmware download on the candidate image and rollback
after schema migration remain untested.

The 2026-11-16 candidate rehearsal used a fresh consistent backup created with
`--no-retention`; the helper briefly stopped hawkBit, verified the archive
checksum, preserved existing archives, and resumed the service. The archive was
restored to an isolated retained PVC with no Service or ingress. Eclipse 1.2.0
started without restarts and Flyway validated six migrations, advancing the
clone from schema 1.20.0 to 1.20.5, including auto-assignment approval. The
existing `therment-01` target and target-token authentication were preserved;
the DDI root and installed-base requests returned HTTP 200. Unauthenticated
Management and DDI requests returned HTTP 401 as expected. The candidate
reported the installed artifact metadata, but following the installed-base
artifact URL returned HTTP 404 even after mounting the copied artifact
repository at the configured path. The same direct installed-base URL also
returned 404 against the restored 1.0.3 control, so this is inconclusive and
does not establish a 1.2.0 regression. No new deployment action was created or
polled, and the download link generated through port-forward was HTTP/localhost;
consequently the actual firmware download and required external HTTPS URL were
not validated. Full Management REST publisher operations and DDI feedback were
not exercised.

Recovery was tested independently by restoring the untouched archive onto a
second PVC and starting the current OpenRemote image. It became Ready without
restarts; Flyway validated the stored schema at 1.20.0 and made no changes. This
demonstrates recovery from the original copy, not rollback from the already
migrated candidate PVC. The candidate log also warns that its H2 2.4.240 version
is newer than Flyway's latest verified H2 2.3.232.

The rehearsal did not verify a complete deployment-action artifact download,
external HTTPS URL behavior, publisher operations or DDI feedback. Keep the
candidate and recovery data isolated; do not cut over. No cutover proposal has
been prepared. Resolve these gaps and pass a full operator-reviewed rehearsal
before considering a production migration.

## Sources

- [Current OpenRemote image repository](https://github.com/openremote/hawkbit)
- [Current OpenRemote image tags](https://hub.docker.com/r/openremote/hawkbit-update-server/tags)
- [Eclipse hawkBit 1.2.0 release](https://github.com/eclipse-hawkbit/hawkbit/releases/tag/1.2.0)
- [Eclipse hawkBit image tags](https://hub.docker.com/r/hawkbit/hawkbit-update-server/tags)
- [Eclipse hawkBit 0.10.0 migration note](https://github.com/eclipse-hawkbit/hawkbit/releases/tag/0.10.0)