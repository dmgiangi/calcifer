# Update module (work in progress)

The module inventories repository configuration and can prepare selected updates locally. Coverage is incomplete: an `unsupported`, `unknown` or `unavailable` result is not an assertion that a component is current. The command returns a nonzero status while coverage remains incomplete. No command commits, pushes, publishes images, runs release workflows, changes the live cluster or upgrades an operating system or K3s host.

Install Python 3.12 and `uv`, then run the commands from the repository root; `uv` manages the workspace environment and the dependencies declared in `update/pyproject.toml`. Human-readable status/check reports use Rich; JSON output remains intended for scripts. The module uses PyYAML and packaging. Some checks need network access and additional tools. Registry checks can use `skopeo` or an anonymous HTTPS fallback for public OCI registries; rendering uses `helm` and GitOps validation uses `kubectl`. Rust lockfile preparation uses `cargo` and its selected toolchain. Private registries may still require `skopeo` and appropriately configured credentials. A missing tool or inaccessible source is reported as a failure or unknown result. Git is required to verify that target files are tracked and clean. Registry credentials must be configured for the isolated tool environment; private registries may therefore require additional configuration.

```bash
uv run calcifer-update status --cluster home
uv run calcifer-update status --json
uv run calcifer-update check --cluster cloud --json
uv run calcifer-update check --offline --json
uv run calcifer-update status --cluster home --live --kubeconfig /path/to/kubeconfig --context-home calcifer-home
uv run calcifer-update status --cluster all --live --kubeconfig /path/to/kubeconfig --context-home calcifer-home --context-cloud calcifer-cloud
```

`status` reads repository declarations and does not contact clusters unless `--live` is given. The inventory and update checks include explicitly declared dependencies only; lockfiles and the Go module graph are used to identify their resolved versions, not to list or check transitive dependencies. Go requirements marked `// indirect` are excluded. `check` queries external release sources and native resolvers; it can take time. GitHub API rate limits can be higher when `GITHUB_TOKEN` or `GH_TOKEN` is configured outside the repository; the token is sent only in the authentication header. It reports progress by phase and completed checks on standard error, keeping standard output valid for `--json`. In an interactive terminal the last three progress messages share three fixed lines; redirected output remains a plain log. `check --offline` skips both remote and live queries and reports unverified results. Live mode uses only read-only `kubectl get` calls with explicit contexts. Flux revisions are compared with local Git HEAD, not uncommitted files. An unreachable cluster is reported separately. Do not put a kubeconfig or credentials in this repository.

Human-readable `status` and `check` output begins with a summary, then groups actionable results under **Azioni da prendere** by dependency kind. Updates, unknowns, unavailable sources, unsupported checks and divergent states appear there; current/configured results are summarized separately under **Nessuna azione immediata**. Use `--json` for the unchanged machine-readable report.

Preparation currently supports selected Helm chart versions, selected image groups (Home Assistant and authorization server), GitHub Actions, the Therment Rust lockfile, directly versioned Maven dependencies in the authorization-server project, direct Python dependencies with a `uv.lock`, and explicitly required Go modules with a `go.sum`. A chart must resolve uniquely for one cluster and its target must be the latest verified stable version from a supported HTTPS chart index. Image updates require an immutable digest and an explicit platform; the anonymous registry fallback is used if `skopeo` is unavailable. Home Assistant needs both tag and digest and updates the deployment and backup references together. Authorization-server promotion must use `--cluster all` so both overlays receive the same digest. Only explicitly mapped image groups are eligible; other components fail closed.

An initial invocation is a preview. Review the complete diff and affected scope, then repeat the command with `--write --expect <fingerprint>` using the fingerprint printed by the preview. Inputs, registry candidates and validation are checked again. The module refuses dirty target files, changed input, unsupported scope and failed validation. Output remains local for ordinary Git review; a later commit and push is an independent operator action.

```bash
uv run calcifer-update apply cert-manager --cluster home --version v1.22.0
uv run calcifer-update apply home-assistant --cluster home --platform linux/amd64 --version '2026.10.0@sha256:REPLACE_WITH_VERIFIED_DIGEST'
uv run calcifer-update apply authorization-server --cluster all --platform linux/amd64 --version 'sha256:REPLACE_WITH_VERIFIED_DIGEST'
uv run calcifer-update apply 'actions/checkout@v4' --cluster all --version v5.0.0
uv run calcifer-update apply rust:therment:log --cluster all --version 0.4.1
uv run calcifer-update apply maven:authorization-server:org.example:library --cluster all --version 1.2.3
uv run calcifer-update apply python:.:example --cluster all --version 1.2.3
uv run calcifer-update apply go:.:example.com/library --cluster all --version v1.2.3
```

These versions and digest placeholders illustrate syntax only; use candidates actually reported by the relevant sources. Dependency preparation uses Maven, uv, Go or Cargo in temporary copies, checks the intended dependency and accepts only expected manifest or lockfile changes. Python preparation requires an existing `uv.lock`; Go preparation requires an existing `go.sum`. Native tools and their appropriate toolchains must be installed. The firmware is built and deployed separately from Flux.

K3s and some bundled components are installed on their hosts. The module does not upgrade them: inspect the observed node versions, review upstream compatibility and release notes, verify backups and recovery, schedule maintenance, and follow the site-specific K3s host procedure. An image rollback is not necessarily a data rollback; validate state and schema compatibility for the affected service.

## Source and effective-state hardening

- Candidate metadata includes `candidate_source`, `comparison_basis`, `current_basis`, `version_policy` and a compatibility warning. `latest-stable` is discovery, not compatibility approval; `same-major` and `same-minor` require a known baseline. An unsupported policy fails closed. Stable SemVer build metadata is retained but does not affect version precedence; prereleases remain excluded.
- If Cargo.lock contains multiple versions of a directly declared crate, discovery uses the declaring package's direct lockfile references when those identify one version. It does not select the highest version arbitrarily or expose transitive crates as independent dependencies. Ambiguous direct references remain unresolved.
- Local Kustomize rendering separates effective image references per cluster from source-only declarations (`effective_state: declaration-only`). Source declarations remain as provenance and are not checked again as deployed images. A failed or unsafe render remains a coverage gap; remote resources and secret-generator inputs are not silently resolved. Rendering does not prove the live deployment matches Git.
- Live K3s observations retain each node version in `node_versions`. A uniform observation can be compared; mixed versions are `divergent`, incomplete data are `unknown`, and failed node queries remain `unavailable`. One successful node cannot stand in for a failed response containing other nodes.
- Bundled Traefik uses the component table from the exact stable K3s release for both baseline and candidate. HelmChart versions are retained separately and are **not** Traefik application versions. `update_via: k3s` forbids treating the result as an independent Traefik upgrade. Release defaults do not prove the installed runtime or override configuration; absent mappings remain unknown.
- POM Java properties describe project targets, not JDK installations. CI JDK declarations are inventoried separately with their distribution and checked only on LTS major lines (8, 11, 17, 21, 25 and subsequent four-major increments): GraalVM Community uses `graalvm/graalvm-ce-builds`, while Temurin uses the corresponding `adoptium/temurin<major>-binaries` releases. Non-LTS declarations do not trigger candidate lookups or recommendations. GraalVM enumerates compact tags for the requested LTS major and verifies exact release metadata (bounded responses and at most 100 candidates); a missing comparable release is unknown, never replaced by another distribution/major. Major-only selectors and Temurin patches without build numbers remain unknown even when an upstream candidate is available.
- Maven effective-POM resolution retains the source coordinate for managed versions. Dependencies whose effective versions are sourced from `org.springframework.boot:spring-boot-dependencies` are reported as parent-managed, not as independent artifact upgrades; the actionable update is the Spring Boot starter parent. Versions sourced from other BOMs or explicitly declared properties remain independently checked.
- Therment records separate pins for `espup` 0.19.0, the Espressif Xtensa Rust release 1.97.0.0, ESP-IDF v5.4.4, `ldproxy` 0.3.5 and `espflash` 4.7.0. The build-only OTA check passed with these inputs; it does not flash or publish. The authorization-server Dockerfile now uses the versioned official `debian:13.7-slim` runtime pinned by digest, replacing Distroless locally; it requires linux/amd64 and preserves the exact Debian 13 zlib package version. Review the base version/digest and zlib security version together. `bash authorization-server/scripts/runtime-smoke.sh <image>` verifies architecture, configured non-root user and UID 10001, live liveness endpoint, CA bundle and read-only-root-filesystem operation. At this local validation checkpoint, the image has not been published or promoted to either cluster.

Hardening tasks 5.1–5.6 and 7.1–7.2 are complete. The approved exact pins are applied: GraalVM Community `jdk-25.0.2`, Temurin `25.0.5+8`, isolated Hatchling `1.33.0`, and the Espressif inputs above. The firmware OTA build check and authorization-server runtime smoke test passed locally. The Hatchling sdist/wheel build, Maven test/package paths, GraalVM native-image compilation and selected native tests also passed. Java recommendations remain limited to LTS lines, and Spring Boot BOM-managed dependencies point to their parent. Hawkbit provenance and the partial isolated rehearsal are documented; deployment-action download, external HTTPS URL, publisher operations and DDI feedback remain unverified, so no cutover is proposed. No firmware was flashed, no image was published, and production image or database was changed. Hawkbit was briefly stopped for its consistent backup and has been resumed; isolated rehearsal PVCs remain retained for follow-up.

## Inventory baseline

The offline inventory includes Helm chart declarations, image references, action references, both K3s and Flux installations, Maven projects, Python dependencies and the Therment Cargo declarations and lockfile. The count can change with the working tree and is not a claim about live deployment. All entries found by that scan had an inventory or check strategy; the scan still reports a coverage gap until Helm chart contents have been rendered. A successful render can itself report unresolved external values, unavailable tools or invalid image references. Mutable tags, managed versions and vendor toolchains can remain unknown even where a strategy exists.

Offline mode and source-failure paths are covered by automated fixtures. Do not interpret missing `helm`, `skopeo`, network access or cluster access as evidence that an update is unavailable. The current local preparation policies cover an explicit subset of image groups and source dependencies; unsupported preparation must be handled and reviewed separately.

The first-party Java target and build JDK remain on the latest released LTS
major, Java 25 as of 2026-11-17. GraalVM Community `25.0.2` remains current for
that distribution; the native authorization runtime does not contain a JRE.
Subsequent live checks confirmed both Cloud and Home on K3s
`v1.37.2+k3s1`, with the bundled Traefik `3.7.15` deployments available.

### Historical hardening checkpoint report

The online check for both clusters, **without live access**, completed with exit status 0 and no coverage/source errors. These are repository/check results, not live deployment or compatibility approval. The earlier session baseline preceded effective-image rendering and CI JDK discovery, so the populations differ:

| Result | Earlier baseline | Hardened inventory |
| --- | ---: | ---: |
| Components | 158 | 191 |
| Configured | 16 | 58 |
| Current | 32 | 34 |
| Update available | 96 | 90 |
| Unknown | 14 | 9 |
| Unavailable / unsupported | 0 | 0 |

The inventory now retains 42 image declarations as source-only provenance and adds 31 effective rendered images plus two explicit CI JDK declarations. The table includes the post-pin check; aggregate status changes therefore do not represent software upgrades. Both exact CI JDK pins now report current against their distribution-specific sources. Therment's direct `toml` dependency resolves through its own Cargo.lock edge to `1.2.1+spec-1.1.0` and is current; the unrelated transitive `toml` version is not checked independently.

All 9 remaining unknowns have identified limits:

- Two K3s installations: no live node versions requested.
- Two bundled Traefik entries: candidate release mappings are available, but exact installed K3s baselines are unobserved.
- Two POM Java targets: language targets do not establish installed JDK identity.
- Espressif Rust: the declared vendor pins build successfully together, but the vendor release does not provide a comparable stable-rust release ordering for update recommendations.
- Distroless: the official `cc-debian13:nonroot` line and exact digest are validated, but the mutable `nonroot` tag does not establish ordered release/version identity; monitor and review digest changes explicitly.
- Hawkbit: registry provenance maps the deployed digest to OpenRemote `1.0.3.0`, but the inventory checker does not yet resolve that image digest to a stable tag; candidate compatibility and migration remain unverified. See the [migration assessment](hawkbit-migration-assessment.md).

The remaining unknowns are two unobserved K3s hosts, two bundled Traefik entries without an observed K3s baseline, two POM Java project targets (not installed toolchains), and one each for Espressif Rust, Distroless and Hawkbit (nine total).

Validation: 132 update-module tests passed; the Hatchling isolated sdist/wheel build passed; authorization-server Maven tests passed (139 tests); GraalVM native-image compilation and selected native tests passed. The pinned ESP32 OTA firmware build completed without flashing or publishing; Therment's host tests passed (10 Python and 27 Rust tests). The authorization-server image built from the pinned bases and passed runtime checks for linux/amd64, the configured non-root user, liveness and CA certificates. Strict OpenSpec validation and `git diff --check` passed. The effective Maven model was also checked against the authorization-server POM: Spring Boot starters resolve to `spring-boot-dependencies`, while the OpenTelemetry exporter resolves to its own BOM. No cluster configuration, registry publication or Git history was changed. No production deployment or K3s host upgrade was run. Hawkbit provenance and a partial isolated rehearsal are documented; the unresolved client/download checks are recorded in its migration assessment.
