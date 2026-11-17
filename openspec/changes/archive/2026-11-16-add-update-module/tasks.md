# Tasks

## 1. Core and inventory

- [x] 1.1 Create the Python module, CLI and result model for `status`, `check` and `apply`; verify help and human/JSON output with CLI tests.
- [x] 1.2 Implement repository discovery for cluster entrypoints, Kustomize bases and overlays, workload images, Helm, build manifests, workflows and firmware; verify fixture and actual-repository inventory coverage.
- [x] 1.3 Create the component catalog with owners, cluster impact, grouped references, sources and validators; verify uncatalogued discoveries fail coverage with their paths.
- [x] 1.4 Implement safe tool execution, timeouts, temporary workspaces and credential redaction; test missing tools, failures and secret-safe output.

## 2. State and update strategies

- [x] 2.1 Add optional explicitly scoped cluster and Flux state collection; test divergent, inaccessible and offline states without live writes.
- [x] 2.2 Implement K3s, bundled component and Flux version discovery and host-managed guidance; test per-cluster and unknown-version cases.
- [x] 2.3 Implement Helm source and chart version checks and chart content resolution; test unresolved content, release selection and partial failures.
- [x] 2.4 Implement OCI registry tag, digest and platform checks across manifests, charts and Dockerfiles; test repeated references, digest-only images and unavailable registries.
- [x] 2.5 Implement Maven and Java dependency, parent, BOM, plugin and toolchain discovery; test managed versus explicit versions and native-tool failures.
- [x] 2.6 Implement Python dependency and build-tool discovery for present declaration and lock formats; test version selection and missing metadata.
- [x] 2.7 Implement Go and Rust dependency, lockfile and toolchain strategies, including Therment; test native resolution and separately deployed firmware classification.
- [x] 2.8 Implement GitHub Actions version discovery and safe candidate selection; test pinned, mutable and unresolvable references.
- [x] 2.9 Verify every discovered existing component has a strategy or an explicit actionable coverage gap; document baseline and exercise offline and source-failure reports.

## 3. Local change preparation

- [x] 3.1 Implement preview and explicit apply with source hashes, temporary workspace, diff and affected-cluster reporting; test no-write preview and stale-input rejection.
- [x] 3.2 Implement transactional file application, conflict detection, allowlists and recovery on write failure; test preservation of unrelated edits and rejection of unexpected output.
- [x] 3.3 Implement chart and grouped OCI reference preparation with digest and platform verification; test Home Assistant deployment and backup consistency.
- [x] 3.4 Implement custom-image promotion using verified published digests and enforce symmetric authorization-server overlays; test no partial writes on either overlay failure.
- [x] 3.5 Implement ecosystem-native preparation of supported dependency and lockfile changes for Java, Python, Go, Rust and CI; test reproducible expected output and missing-tool handling.
- [x] 3.6 Add applicable syntax, dependency and Kustomize validation and fail closed for unsupported, ambiguous or host-managed updates; test validation failures leave the checkout unchanged.

## 4. Verification and operations

- [x] 4.1 Add end-to-end fixture tests for both clusters, shared components, unavailable sources, coverage failures and transaction rollback; run the suite without live credentials.
- [x] 4.2 Document installation, required native tools, online/offline checks, explicit scope, output statuses, preview/apply, release boundaries and host upgrade guidance; verify examples against CLI help.
- [x] 4.3 Run repository-wide inventory and coverage checks plus relevant automated tests, record limitations, and verify no cluster, registry, Git history or secrets were modified.

## 5. Source, version and effective-state hardening

- [x] 5.1 Define per-component authoritative sources, stable/compatibility policies and candidate provenance; extend version parsing to handle SemVer build metadata without confusing it with prerelease ordering, with regression tests for Rust package versions.
- [x] 5.2 Resolve effective Kustomize images per cluster so overridden base placeholders are excluded from deployed inventory; test divergent overlays and rendered image references.
- [x] 5.3 Extend live K3s observations to retain every node's version and report mixed versions; map bundled Traefik only from authoritative metadata for the exact K3s release and keep it non-independent.
- [x] 5.4 Make Java toolchain checks distribution-aware (GraalVM Community versus Temurin) and pin exact versions in build workflows; pin Hatchling for isolated builds and validate reproducibility.
- [x] 5.5 Pin the Espressif Rust installer/toolchain inputs separately, verify ESP-IDF compatibility, and run firmware build validation without flashing or publishing.
- [x] 5.6 Select maintained official base-image lines and verified digests for Distroless and its runtime-library build stage; validate architecture, native-library loading, certificates and non-root startup, while documenting digest monitoring.

## 6. Hawkbit migration assessment

- [x] 6.1 Establish current image provenance, configuration, authentication, API/client expectations and persistent-data behavior; identify an authoritative Eclipse Hawkbit image/release candidate without assuming it is drop-in compatible.

## 7. Verification and reporting

- [x] 7.1 Add regression tests for source provenance, compatibility-policy filtering, missing version mappings, effective overlays, mixed K3s nodes and bundled Traefik mapping.
- [x] 7.2 Update operator documentation and report the before/after status counts with reasons; verify remaining unknown/unavailable results are genuine gaps or source failures and no live deployment or host upgrade was performed.

## Implementation checkpoint

Tasks 5.1–5.6, 6.1 and 7.1–7.2 are implemented. The remaining Hawkbit rehearsal and cutover items were removed from this change at the operator's request; this does not mark migration compatibility or cutover as verified. The assessment documents the partial isolated rehearsal and outstanding OTA download, HTTPS URL, publisher and feedback checks. Exact pins include GraalVM Community `jdk-25.0.2`, Temurin `25.0.5+8`, Hatchling `1.33.0`, and Therment's `espup` 0.19.0, Xtensa Rust 1.97.0.0, ESP-IDF v5.4.4, `ldproxy` 0.3.5 and `espflash` 4.7.0. The build-only OTA firmware check passed with the declared Espressif inputs. Authorization-server's Debian 13/Distroless Debian 13 amd64 image built from pinned digests and passed runtime architecture, zlib loading, CA certificate, non-root and liveness checks. The update-module suite passed (132 tests), Therment host tests passed (10 Python and 27 Rust), and earlier Hatchling, Java native-image and authorization-server tests pass. Java build recommendations remain restricted to LTS major lines. Spring Boot BOM-managed Maven dependencies are attributed to the parent, while independently sourced BOM versions remain checkable. A fresh Hawkbit backup and two isolated PVC copies were created on calcifer-home; production image and database were not changed, and the live service was resumed after backup. Operator documentation records the post-pin report: 191 components, 58 configured, 34 current, 90 update-available, 9 explained unknowns and no coverage/source errors. The update-module suite includes direct Cargo.lock edge resolution when multiple crate versions exist.
