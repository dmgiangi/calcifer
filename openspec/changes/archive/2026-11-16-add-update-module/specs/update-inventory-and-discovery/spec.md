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

### Requirement: Use authoritative sources and explicit version policies
The module SHALL record the source and comparison basis for each candidate and SHALL prefer the component's authoritative upstream release, package, registry or toolchain source. Candidate selection SHALL follow an explicit stability and compatibility policy for that component rather than assuming the newest upstream release is acceptable. The report SHALL distinguish declared, resolved and observed versions and SHALL NOT infer a version from an unverified digest or missing data.

#### Scenario: Candidate is newer but outside the supported compatibility line
- **WHEN** an upstream source lists a newer stable version that is not allowed by the component policy
- **THEN** the module does not present it as an applicable update and explains the selected policy or compatibility limit

#### Scenario: Source cannot establish a version
- **WHEN** a digest, tag or vendor-managed toolchain has no verified mapping to a comparable version
- **THEN** the report preserves the raw reference, identifies the source limitation and leaves ordering unknown

### Requirement: Resolve effective cluster declarations and bundled versions
The module SHALL evaluate image references after applying the relevant Kustomize overlays for each cluster and SHALL distinguish overridden base placeholders from effective references. Live K3s observation SHALL use explicit cluster contexts and account for every node, reporting mixed or unavailable observations without collapsing them into a false single version. A K3s-bundled component SHALL be compared only when an authoritative manifest or release source maps it to the exact K3s release; it SHALL NOT be proposed as an independent host update.

#### Scenario: Overlay replaces a base placeholder
- **WHEN** a cluster overlay replaces an image placeholder declared in a shared base
- **THEN** the effective inventory checks the rendered cluster image and does not report the replaced placeholder as deployed

#### Scenario: K3s nodes disagree
- **WHEN** live nodes in one explicitly selected cluster report different K3s versions
- **THEN** the report retains the per-node versions and identifies the cluster as divergent rather than choosing one arbitrarily

#### Scenario: Bundled component mapping is absent
- **WHEN** the exact K3s release source does not establish the bundled component version
- **THEN** the component remains unknown and no independent update candidate is asserted

### Requirement: Report reproducible toolchain and base-image identity
The inventory SHALL distinguish project language targets from the actual compiler or runtime distribution/version used to build or run a component. It SHALL record exact toolchain identity where declared, including Java distribution, isolated Python build backend and Espressif vendor-toolchain versions. Container base-image checks SHALL identify the supported upstream line and verified digest separately from release-version ordering. Missing pins or unsupported compatibility mappings SHALL remain visible as coverage gaps or unknown results.

#### Scenario: Java distributions differ
- **WHEN** projects use different Java distributions, including GraalVM Community and Eclipse Temurin
- **THEN** each toolchain is checked against its own authoritative source and the project target version is not misreported as the installed patch version

#### Scenario: Java build toolchain is not LTS
- **WHEN** a CI build JDK declaration uses a non-LTS major line
- **THEN** the module does not query or recommend a non-LTS candidate

#### Scenario: Spring Boot BOM supplies an explicit dependency version
- **WHEN** an unversioned dependency's effective version is sourced from the Spring Boot dependency BOM
- **THEN** the module attributes the update to the Spring Boot parent instead of recommending an independent artifact upgrade

#### Scenario: Espressif Rust toolchain is vendor-managed
- **WHEN** firmware uses an Espressif-managed Rust toolchain
- **THEN** the report records the installer and toolchain versions separately and checks the declared ESP-IDF/toolchain compatibility instead of comparing it with stable rust-lang releases

#### Scenario: Base image is digest-pinned
- **WHEN** a base image uses a verified digest and a mutable upstream tag for update monitoring
- **THEN** the report preserves the digest for reproducibility and reports tag/release provenance separately without treating a digest change alone as a version upgrade

### Requirement: Assess stateful image migrations before proposing cutover
For stateful services whose current image is digest-only, the module SHALL identify the current provenance and require evidence of configuration, API, storage and data compatibility before treating an upstream image as a migration candidate. Preparation SHALL NOT imply production cutover or database migration. Migration guidance SHALL require an isolated rehearsal using copied data and a documented recovery plan where persistent state or schema can change.

#### Scenario: Upstream image appears to be a possible replacement
- **WHEN** an authoritative upstream image is identified for a service currently using another image distribution
- **THEN** the report presents it only as a migration candidate until configuration, API, persistent-data and client compatibility have been validated

### Requirement: Keep inspections read-only and secret-safe
Status and check operations SHALL leave repository files and live clusters unchanged, avoid disclosing credentials and distinguish errors from successful no-update results. The module SHALL provide human-readable output and machine-readable results suitable for coverage checks.

#### Scenario: Check with private credentials configured
- **WHEN** a registry or cluster check fails authentication
- **THEN** the result identifies the failure without displaying credential values
