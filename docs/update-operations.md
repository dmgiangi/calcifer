# Update module (work in progress)

The module inventories repository configuration and can prepare selected updates locally. Coverage is incomplete: an `unsupported`, `unknown` or `unavailable` result is not an assertion that a component is current. The command returns a nonzero status while coverage remains incomplete. No command commits, pushes, publishes images, runs release workflows, changes the live cluster or upgrades an operating system or K3s host.

Install Python 3.12 and `uv`, then run the commands from the repository root; `uv` manages the workspace environment and the dependencies declared in `update/pyproject.toml`. The module uses PyYAML and packaging. Some checks need network access and additional tools. Registry checks can use `skopeo` or an anonymous HTTPS fallback for public OCI registries; rendering uses `helm` and GitOps validation uses `kubectl`. Rust lockfile preparation uses `cargo` and its selected toolchain. Private registries may still require `skopeo` and appropriately configured credentials. A missing tool or inaccessible source is reported as a failure or unknown result. Git is required to verify that target files are tracked and clean. Registry credentials must be configured for the isolated tool environment; private registries may therefore require additional configuration.

```bash
uv run calcifer-update status --cluster home
uv run calcifer-update status --json
uv run calcifer-update check --cluster cloud --json
uv run calcifer-update check --offline --json
uv run calcifer-update status --cluster home --live --kubeconfig /path/to/kubeconfig --context-home calcifer-home
uv run calcifer-update status --cluster all --live --kubeconfig /path/to/kubeconfig --context-home calcifer-home --context-cloud calcifer-cloud
```

`status` reads repository declarations and does not contact clusters unless `--live` is given. The inventory and update checks include explicitly declared dependencies only; lockfiles and the Go module graph are used to identify their resolved versions, not to list or check transitive dependencies. Go requirements marked `// indirect` are excluded. `check` queries external release sources and native resolvers; it can take time. GitHub API rate limits can be higher when `GITHUB_TOKEN` or `GH_TOKEN` is configured outside the repository; the token is sent only in the authentication header. It reports progress by phase and completed checks on standard error, keeping standard output valid for `--json`. In an interactive terminal the last three progress messages share three fixed lines; redirected output remains a plain log. `check --offline` skips both remote and live queries and reports unverified results. Live mode uses only read-only `kubectl get` calls with explicit contexts. Flux revisions are compared with local Git HEAD, not uncommitted files. An unreachable cluster is reported separately. Do not put a kubeconfig or credentials in this repository.

Preparation currently supports selected Helm chart versions, selected image groups (Home Assistant, authorization server and Rage Quit), GitHub Actions the Therment Rust lockfile, directly versioned Maven dependencies in the two custom services, direct Python dependencies with a `uv.lock`, and explicitly required Go modules with a `go.sum`. A chart must resolve uniquely for one cluster and its target must be the latest verified stable version from a supported HTTPS chart index. Image updates require an immutable digest and an explicit platform; the anonymous registry fallback is used if `skopeo` is unavailable. Home Assistant needs both tag and digest and updates the deployment and backup references together. Authorization-server promotion must use `--cluster all` so both overlays receive the same digest. Only explicitly mapped image groups are eligible; other components fail closed.

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

## Inventory baseline

The offline inventory includes Helm chart declarations, image references, action references, both K3s and Flux installations, Maven projects, Python dependencies and the Therment Cargo declarations and lockfile. The count can change with the working tree and is not a claim about live deployment. All entries found by that scan had an inventory or check strategy; the scan still reports a coverage gap until Helm chart contents have been rendered. A successful render can itself report unresolved external values, unavailable tools or invalid image references. Mutable tags, managed versions and vendor toolchains can remain unknown even where a strategy exists.

Offline mode and source-failure paths are covered by automated fixtures. Do not interpret missing `helm`, `skopeo`, network access or cluster access as evidence that an update is unavailable. The current local preparation policies cover an explicit subset of image groups and source dependencies; unsupported preparation must be handled and reviewed separately.
