## ADDED Requirements

### Requirement: Native releases are manually initiated and immutable
The repository SHALL contain a `workflow_dispatch`-only GitHub Actions workflow that validates an explicit version, builds/tests the Java 25 module, builds a linux/amd64 native image, and publishes an immutable GHCR reference.

#### Scenario: Maintainer dispatches valid release
- **WHEN** an authorized maintainer dispatches the workflow with a valid version
- **THEN** it SHALL build/test the native image and publish its immutable GHCR artifact

#### Scenario: Invalid release input is provided
- **WHEN** the workflow receives invalid or unsafe version input
- **THEN** it SHALL fail before publishing an image or changing GitOps manifests

### Requirement: Release promotion is explicit and digest pinned
After publication, the workflow SHALL update cloud GitOps configuration with the published image digest in a Flux-reconcilable commit. It SHALL prevent concurrent promotions and SHALL not use mutable tags such as `latest` as deployment input.

#### Scenario: Published release is promoted
- **WHEN** a native image is successfully published to GHCR
- **THEN** the workflow SHALL commit its digest-pinned reference to cloud Kustomize configuration

#### Scenario: Maintainer rolls back a release
- **WHEN** the promoted GitOps commit is reverted to a prior digest
- **THEN** Flux SHALL be able to reconcile the prior image without another build
