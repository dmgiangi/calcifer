## MODIFIED Requirements

### Requirement: One immutable release is promoted to both clusters
The repository SHALL contain a `workflow_dispatch`-only workflow that validates
an explicit version, builds/tests the Java 25 module, builds a linux/amd64
native image, and publishes an immutable GHCR reference. Successful promotion
SHALL update both Cloud and Home overlays to the same image digest.

#### Scenario: Maintainer dispatches a valid release
- **WHEN** an authorized maintainer dispatches the workflow with a valid
  version
- **THEN** it SHALL publish one immutable artifact and promote its digest to
  both overlays

#### Scenario: Invalid version is provided
- **WHEN** the workflow receives invalid or unsafe version input
- **THEN** it SHALL fail before publishing or modifying either overlay

### Requirement: Rollback is symmetric
The GitOps configuration SHALL allow a prior image digest to be restored for
both clusters without rebuilding the image.

#### Scenario: Maintainer rolls back a release
- **WHEN** the promotion commit is reverted
- **THEN** Flux SHALL reconcile the prior digest in both clusters
