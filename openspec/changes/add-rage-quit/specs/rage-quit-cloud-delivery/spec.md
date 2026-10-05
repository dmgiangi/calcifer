# Rage Quit Cloud delivery

## Purpose

Define Cloud-only, encrypted and reproducible delivery of Rage Quit with persistent state, private operational endpoints, and a verified recovery procedure.

## ADDED Requirements

### Requirement: Rage Quit is delivered only to the Cloud web domain
GitOps SHALL deploy Rage Quit in `calcifer-cloud`'s `web` namespace and expose it at `https://rage-quit.calcifer.tech` using the existing Cloud edge and certificate platform. Its reconciliation SHALL depend on authorization-server and certificate readiness without imposing new dependencies on unrelated applications. No Rage Quit workload, PVC, or route SHALL be installed in `calcifer-home`; minimum shared authorization configuration and existing symmetric auth promotion SHALL preserve the logical issuer contract.

#### Scenario: Cluster roots are rendered
- **WHEN** Cloud and Home manifests are rendered
- **THEN** Cloud contains the Rage Quit workload and route while Home contains no Rage Quit application resources

#### Scenario: Authentication dependency is unavailable
- **WHEN** the authorization-server dependency has not reached readiness during initial reconciliation
- **THEN** Rage Quit activation waits without introducing a new dependency for unrelated Cloud applications

### Requirement: Workload state is persistent and single-writer
The workload SHALL have one active writer and use persistent Cloud storage across restarts and updates. Updates SHALL NOT overlap writers. Removing or pruning application resources SHALL NOT automatically delete its database PVC. The application SHALL run without Kubernetes API write permissions, as non-root with restricted privileges, bounded resources and writable paths, and usable health probes. Storage/schema failure SHALL prevent readiness without erasing data; external auth outages SHALL NOT alone fail liveness.

#### Scenario: Image update is reconciled
- **WHEN** Flux changes the application image
- **THEN** the prior writer terminates before its replacement uses the same database and existing records remain intact

#### Scenario: Application reconciliation is removed
- **WHEN** ordinary Flux pruning removes the application's resources
- **THEN** the persistent database claim remains available for deliberate recovery or separately authorized deletion

#### Scenario: Issuer becomes temporarily unreachable
- **WHEN** the application process and database are healthy but the issuer is unavailable
- **THEN** new login may fail but liveness does not trigger an application restart loop

### Requirement: Releases are immutable and scoped to Cloud
The repository SHALL provide a manually dispatched release workflow that validates an explicit version, runs focused tests and container/manifest checks, publishes the application image, and promotes an immutable digest only to its Cloud overlay. Failed validation SHALL NOT publish or promote a release. A compatible prior digest SHALL be restorable without rebuilding or removing data. Authorization-server releases SHALL retain their separate existing symmetric behavior.

#### Scenario: Maintainer dispatches a valid Rage Quit release
- **WHEN** authorized release validation succeeds
- **THEN** one immutable application image is published and only the Rage Quit Cloud image reference is promoted

#### Scenario: Release validation fails
- **WHEN** application tests or manifest/container validation fail
- **THEN** the workflow does not publish or promote the application release

### Requirement: Secrets and edge prerequisites are explicit
Client secrets SHALL be supplied through SOPS-encrypted Kubernetes Secrets, never plaintext committed credentials. Non-secret configuration SHALL be separate. Deployment documentation SHALL describe required DNS, certificate, OIDC callback, client-secret provisioning, and rollout prerequisites. HTTP tracking requests SHALL redirect to HTTPS or be refused; HTTPS SHALL use a valid hostname certificate. Live deployment and secret/DNS mutation SHALL require explicit operator authorization.

#### Scenario: Client secret is unavailable
- **WHEN** a required application or authorization-server client secret is missing
- **THEN** readiness/startup fails closed rather than registering or accepting an empty client secret

#### Scenario: User visits the public hostname
- **WHEN** the user requests Rage Quit over HTTP or HTTPS
- **THEN** tracking data is served only over valid HTTPS and remains protected by application authentication

### Requirement: Backup and restore are verified and preserve rollback
The change SHALL provide a documented operator-run consistent database backup, integrity validation, encrypted snapshots in protected off-node storage, and guarded restore procedure. Snapshots SHALL be encrypted before off-node transfer or retention, with operator-controlled keys kept out of repository files, logs, and command-line values. Restore SHALL stop writers, preserve recoverable pre-restore state, decrypt and validate the selected snapshot and schema, restore data/permissions, and verify events and insights before service resumes. Backups SHALL NOT be public HTTP resources or committed repository files. Recovery SHALL be demonstrated on disposable storage before operational acceptance; no live overwrite is authorized by proposal or local verification alone.

#### Scenario: Operator creates a backup
- **WHEN** an authorized operator follows the backup procedure
- **THEN** the resulting consistent snapshot passes integrity validation and is encrypted before off-node transfer or retention without exposing records publicly

#### Scenario: Candidate restore snapshot is invalid
- **WHEN** a selected snapshot fails integrity or schema validation
- **THEN** restore does not overwrite the recoverable current database or start the app with that snapshot

#### Scenario: Recovery is rehearsed
- **WHEN** a valid snapshot is restored onto disposable storage
- **THEN** the restored event counts, declarations, start date, scores, intervals, and charts agree with the backed-up state
