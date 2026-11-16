# Update local preparation

## Purpose

Let an operator prepare coherent, inspectable repository changes for selected updates without deploying them.

## ADDED Requirements

### Requirement: Preview and prepare selected updates
The module SHALL accept a specific component, target version or immutable digest and applicable cluster scope, show the candidate, affected files and clusters and a diff before writing, and write only after an explicit request. It SHALL validate source and target references and refuse ambiguous scope, unsafe or unresolved changes.

#### Scenario: Preview a chart update
- **WHEN** an operator requests a preview for a resolvable chart update
- **THEN** the module shows the affected HelmRelease, target version, cluster impact and diff without changing repository files

#### Scenario: Explicit local preparation
- **WHEN** an operator explicitly requests preparation of a validated update
- **THEN** the module writes the reviewed changes locally and reports the resulting files and diff

### Requirement: Preserve coordinated references and release invariants
The module SHALL update all mapped references that must change together, including repeated images and backup jobs, and SHALL report shared-base impact. Authorization-server image promotion SHALL use the same verified published digest in both cluster overlays, consistent with the existing symmetric release requirement. Custom-service dependency updates SHALL be distinguished from image publication and subsequent deployment.

#### Scenario: Update Home Assistant image
- **WHEN** an operator prepares an eligible Home Assistant image update
- **THEN** all mapped references in its deployment and backup job use the selected version and matching digest

#### Scenario: Authorization-server promotion
- **WHEN** an operator prepares a published authorization-server image promotion
- **THEN** both cluster overlays receive the same verified digest or no files are changed

### Requirement: Preserve local work and validate changes
Preparation SHALL preserve unrelated existing edits and SHALL stop without partial repository changes if inputs changed since preview, an affected file has conflicting local edits, a native tool fails, an unexpected file changes or validation fails. It SHALL use ecosystem-native tools when appropriate and verify applicable rendered configuration, syntax or dependency consistency before applying the prepared changes.

#### Scenario: Conflicting local edits
- **WHEN** a target file has local changes that cannot be safely preserved
- **THEN** preparation stops and reports the conflict without overwriting the file

#### Scenario: Native tool modifies an unexpected file
- **WHEN** a dependency update produces an unapproved file change
- **THEN** no prepared changes are copied into the repository and the unexpected output is reported

#### Scenario: Prepare a reproducible toolchain update
- **WHEN** an operator requests a toolchain or base-image pin update
- **THEN** preparation records the exact supported distribution/version or verified digest, validates the relevant isolated build/render, and refuses a candidate without source or compatibility evidence

### Requirement: Maintain an operator-controlled deployment boundary
The module SHALL NOT commit, push, publish images, dispatch release workflows, modify live cluster resources or upgrade hosts. When a change cannot be represented safely in repository files, it SHALL report the limitation and actionable operator steps instead of claiming an update was applied.

#### Scenario: K3s host upgrade
- **WHEN** an operator selects a K3s update whose version is managed on a host
- **THEN** the module reports the relevant host-managed procedure and does not modify the host or claim a GitOps change

#### Scenario: Hawkbit migration requires persistent-data validation
- **WHEN** a Hawkbit image migration is prepared
- **THEN** the workflow requires an isolated rehearsal against a copy of persistent data and reviewed recovery guidance, and does not modify production data or deploy the image
