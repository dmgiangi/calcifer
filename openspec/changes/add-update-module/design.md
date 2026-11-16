# Design

## Context

Flux watches `master` for two K3s clusters. Shared bases and overlays coexist with cluster-specific manifests; authorization-server promotion has an existing symmetric two-cluster release contract. Images appear in several workloads and backup jobs; Helm can introduce further images. K3s and bundled Traefik have host-managed aspects. Custom code includes Maven services and Therment Rust firmware; Python tooling and CI workflows are also present.

## Goals / Non-Goals

**Goals:** An extensible, complete inventory with explicit coverage gaps; ecosystem-aware candidate discovery; reviewable local preparation preserving existing work and release invariants.

**Non-Goals:** Automatic deployment, host mutation, Git commits or pushes, image publication, workflow dispatch, or replacing existing release and backup procedures.

## Decisions

1. **Discovery plus explicit policies.** Scan tracked project declarations and cluster entrypoints, then resolve components through a versioned catalog specifying ownership, sources, scopes, grouped references and validators. Compare scan output to the catalog and fail coverage on gaps. Pure hard-coded inventory risks missing new services; unbounded automatic rewriting cannot establish semantic ownership. Classify firmware separately from cluster workloads. Render overlays and chart content where possible, reporting unresolvable nested contents and sources explicitly.
2. **Pluggable strategies behind one data model.** Python CLI offers `status`, `check` and `apply`; results carry provenance, declared/observed/candidate state, errors, scope and capability. Implement strategies for K3s/Flux, Helm, OCI, Maven, Python, Go, Rust and Actions, covering current repository declarations and supporting future projects. Respect version conventions and prerelease policies per source rather than assuming all tags are SemVer; verify image digests and platform compatibility. Explicitly surface unknown digest-to-version mapping and dependency resolution limitations.
3. **Read-only discovery with optional live observation.** Repository inventory works offline; opt-in `kubectl`/Flux queries use explicit contexts and read-only operations. External registry and package lookups may require network and local authentication; partial failures remain visible. Native check commands run in a temporary copy with isolated caches where necessary so they cannot alter the checkout. Avoid logging credentials or secret content, including SOPS plaintext.
4. **Prepare transactionally.** Preview a selected version and scope using a temporary copy of relevant files; native ecosystem tools may generate lockfiles there. Validate expected file allowlists, complete change groups, relevant Kustomize renders and syntax, version/digest correspondence and existing compatibility invariants. On explicit apply, recheck input hashes and local modifications, then copy validated results with rollback if a write fails. Reject conflicting edits, unexpected changes or unverifiable scope. No implicit commit or live action.
5. **Existing release paths remain authoritative.** Custom dependency updates are source changes; existing release workflows build and publish images. A separate local promotion can target an already published verified digest without bypassing symmetric authorization-server overlay requirements. K3s host upgrades and other non-GitOps operations result in guidance, not simulated changes. Generated Flux files are handled with the appropriate supported Flux generation mechanism or reported as requiring manual handling.
6. **Prefer authoritative sources and explicit compatibility policies.** Each candidate records its upstream source and comparison basis. Select the latest stable candidate allowed by the component's support and compatibility policy, not automatically the newest release. Preserve declared, resolved and observed versions separately; never infer a release version from a digest or a missing observation.
7. **Evaluate effective cluster configuration and bundled versions.** Resolve Kustomize overlays per cluster before classifying image references, so overridden base placeholders are not reported as deployed. Live K3s checks use explicitly selected contexts and account for every node, surfacing mixed versions. Bundled Traefik is compared only when the exact K3s release metadata establishes its version; it is not proposed as an independent upgrade.
8. **Pin toolchain identity separately from project compatibility.** Record exact toolchain distribution and version in the build path, and keep project target versions distinct from the installed compiler/runtime. Java checks distinguish GraalVM Community from Temurin; Hatchling's build-backend version is reproducible in isolated builds; Espressif Rust records both espup and the installed vendor toolchain and verifies their ESP-IDF compatibility. Firmware validation builds only and does not flash or publish.
9. **Use maintained, immutable runtime inputs.** Select actively supported official base-image lines, pin deployed/build inputs by verified digest where reproducibility requires it, and retain a monitoring source for newer digests. A digest change alone is not proof of a newer software version or compatibility; validate native libraries, certificates, architecture and non-root startup.
10. **Treat Hawkbit as a staged stateful migration.** Establish the current image's provenance and behavior, then assess an Eclipse-maintained release image against configuration, authentication, DDI/API, storage and client requirements. Test with an isolated copy of persistent data and document recovery before any production cutover. Do not automatically change the image or database as part of ordinary candidate discovery.

## Risks / Trade-offs

- [Registry, chart and package metadata do not always map reliably to deployments or releases] → show provenance and unknowns, do not guess or offer unsafe writes.
- [Full discovery and native resolution can be expensive or require network/toolchains] → bounded timeouts, caching where safe, partial results and documented prerequisites.
- [Tools can touch caches or unexpected files] → temporary workspace, isolated writable paths, strict output allowlists and validation before copying changes.
- [Stateful migrations and cross-cluster compatibility may preclude rollback] → surface relevant release notes and backup/compatibility guidance; do not assert automatic rollback safety.
- [Helm templating and dynamic build declarations can resist static discovery] → use native resolution where possible and make unresolved coverage visible.
- [A maintained Hawkbit image may differ in configuration, API behavior or database schema from the current image] → require an isolated migration rehearsal, data backup and explicit operator approval; do not assume rollback is safe after schema changes.
- [Digest pinning improves reproducibility but can obscure upstream version ordering] → report digest provenance and monitor the corresponding official tag/release separately.

## Migration Plan

Introduce the module and catalog without changing deployed versions. Establish baseline coverage against the repository and document prerequisites; operators adopt inspection first and review local diffs before their normal GitOps process. Roll back the module like ordinary repository code; any prepared changes remain separate and reviewable.

The follow-up hardening is delivered in reviewable stages: first improve source/version policy and effective-state reporting, then pin and validate toolchains and base images, and finally rehearse Hawkbit migration against isolated data. No production rollout, host upgrade, image publication or live database migration is implied by these tasks. Any Hawkbit cutover requires a separately reviewed change after compatibility and recovery evidence is available.
