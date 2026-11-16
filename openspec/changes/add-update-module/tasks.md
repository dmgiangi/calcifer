# Tasks

## 1. Core and inventory

- [ ] 1.1 Create the Python module, CLI and result model for `status`, `check` and `apply`; verify help and human/JSON output with CLI tests.
- [ ] 1.2 Implement repository discovery for cluster entrypoints, Kustomize bases and overlays, workload images, Helm, build manifests, workflows and firmware; verify fixture and actual-repository inventory coverage.
- [ ] 1.3 Create the component catalog with owners, cluster impact, grouped references, sources and validators; verify uncatalogued discoveries fail coverage with their paths.
- [ ] 1.4 Implement safe tool execution, timeouts, temporary workspaces and credential redaction; test missing tools, failures and secret-safe output.

## 2. State and update strategies

- [ ] 2.1 Add optional explicitly scoped cluster and Flux state collection; test divergent, inaccessible and offline states without live writes.
- [ ] 2.2 Implement K3s, bundled component and Flux version discovery and host-managed guidance; test per-cluster and unknown-version cases.
- [ ] 2.3 Implement Helm source and chart version checks and chart content resolution; test unresolved content, release selection and partial failures.
- [ ] 2.4 Implement OCI registry tag, digest and platform checks across manifests, charts and Dockerfiles; test repeated references, digest-only images and unavailable registries.
- [ ] 2.5 Implement Maven and Java dependency, parent, BOM, plugin and toolchain discovery; test managed versus explicit versions and native-tool failures.
- [ ] 2.6 Implement Python dependency and build-tool discovery for present declaration and lock formats; test version selection and missing metadata.
- [ ] 2.7 Implement Go and Rust dependency, lockfile and toolchain strategies, including Therment; test native resolution and separately deployed firmware classification.
- [ ] 2.8 Implement GitHub Actions version discovery and safe candidate selection; test pinned, mutable and unresolvable references.
- [ ] 2.9 Verify every discovered existing component has a strategy or an explicit actionable coverage gap; document baseline and exercise offline and source-failure reports.

## 3. Local change preparation

- [ ] 3.1 Implement preview and explicit apply with source hashes, temporary workspace, diff and affected-cluster reporting; test no-write preview and stale-input rejection.
- [ ] 3.2 Implement transactional file application, conflict detection, allowlists and recovery on write failure; test preservation of unrelated edits and rejection of unexpected output.
- [ ] 3.3 Implement chart and grouped OCI reference preparation with digest and platform verification; test Home Assistant deployment and backup consistency.
- [ ] 3.4 Implement custom-image promotion using verified published digests and enforce symmetric authorization-server overlays; test no partial writes on either overlay failure.
- [ ] 3.5 Implement ecosystem-native preparation of supported dependency and lockfile changes for Java, Python, Go, Rust and CI; test reproducible expected output and missing-tool handling.
- [ ] 3.6 Add applicable syntax, dependency and Kustomize validation and fail closed for unsupported, ambiguous or host-managed updates; test validation failures leave the checkout unchanged.

## 4. Verification and operations

- [ ] 4.1 Add end-to-end fixture tests for both clusters, shared components, unavailable sources, coverage failures and transaction rollback; run the suite without live credentials.
- [ ] 4.2 Document installation, required native tools, online/offline checks, explicit scope, output statuses, preview/apply, release boundaries and host upgrade guidance; verify examples against CLI help.
- [ ] 4.3 Run repository-wide inventory and coverage checks plus relevant automated tests, record limitations, and verify no cluster, registry, Git history or secrets were modified.
