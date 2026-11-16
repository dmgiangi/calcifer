# Update inventory and discovery

## Purpose

Provide a complete, honest view of the components and dependency updates relevant to the repository and both clusters.

## ADDED Requirements

### Requirement: Discover components and coverage
The module SHALL discover components and dependency declarations across both clusters, shared bases and overlays, Helm and K3s-managed infrastructure, custom services, build definitions, CI workflows and separately deployed firmware. It SHALL associate components with their source and affected clusters or identify them as external to cluster deployment. It SHALL report discovered components without an applicable strategy as unsupported and make the coverage check fail.

#### Scenario: New unrecognized dependency declaration
- **WHEN** discovery finds a component or supported manifest type without a strategy or configured mapping
- **THEN** the report identifies its location and coverage fails rather than silently omitting it

#### Scenario: Shared application
- **WHEN** a shared base is used by both clusters
- **THEN** its inventory entry identifies both affected clusters and the relevant overlays

### Requirement: Report declared and observed state separately
The module SHALL expose declared versions and references and, when requested and accessible, observed cluster state and reconciliation health, with cluster contexts explicitly selected. It SHALL remain useful without live cluster access and distinguish unobserved, unavailable, failed and divergent states from healthy or current states.

#### Scenario: One cluster is unreachable
- **WHEN** a live status check cannot reach one cluster
- **THEN** the report retains available repository and other-cluster results and marks that cluster's observations unavailable

### Requirement: Discover updates with ecosystem-specific rules
The module SHALL check applicable K3s, Flux, Helm, OCI, Maven/Java, Python, Go, Rust and CI workflow components using appropriate version and source rules, including build dependencies, image bases and lockfiles. It SHALL identify current and candidate versions or digests, source and affected components, and distinguish stable candidates from prereleases and incompatible or unresolvable candidates. It SHALL report image dependencies supplied by charts where these can be resolved and explicitly identify unresolved chart contents.

#### Scenario: Registry or dependency source fails
- **WHEN** a source cannot be queried or a required native tool is missing
- **THEN** the affected checks report the cause and unknown status, not up-to-date status

#### Scenario: Digest-only image
- **WHEN** a declared digest cannot be reliably mapped to a release version
- **THEN** the report preserves the digest and marks the version unknown without guessing

### Requirement: Keep inspections read-only and secret-safe
Status and check operations SHALL leave repository files and live clusters unchanged, avoid disclosing credentials and distinguish errors from successful no-update results. The module SHALL provide human-readable output and machine-readable results suitable for coverage checks.

#### Scenario: Check with private credentials configured
- **WHEN** a registry or cluster check fails authentication
- **THEN** the result identifies the failure without displaying credential values
