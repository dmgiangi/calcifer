# Proposal

## Why

Cluster upgrades, third-party images and charts, and custom-service dependencies are managed through different mechanisms, making it hard to see what is current and prepare coherent updates. A single operator-facing module should inventory both clusters and the repository, report available updates and prepare reviewable local changes.

## What Changes

- Add a Python `update` module with status, update discovery and explicitly requested local change preparation.
- Inventory cluster infrastructure, Flux, K3s-managed components, Helm charts, container images, custom applications and their build and runtime dependencies, including the separately deployed Therment firmware.
- Use ecosystem-specific strategies and native tools where helpful for OCI images, Helm, Maven/Java, Python, Go, Rust and CI workflows; identify unsupported or undiscoverable components explicitly.
- Compare declared and, where accessible, observed state with available releases; report unknowns, errors, compatibility considerations and the provenance of candidates.
- Prefer authoritative upstream sources and explicit per-component compatibility policies; distinguish reproducible versions from mutable references and report the source and basis for every candidate.
- Refine discovery to account for effective per-cluster Kustomize overlays, all observed K3s nodes and components bundled with K3s, avoiding false unknowns without inventing version data.
- Improve reproducibility for Java, Python build tooling, Espressif Rust toolchains and container base images by recording exact distributions/versions and verified image digests where applicable.
- Assess a staged migration of the Hawkbit image to an authoritative upstream distribution, including configuration, API and persistent-data compatibility; do not treat a candidate image as a drop-in replacement without validation.
- Prepare coherent local changes for supported GitOps and dependency updates, with affected clusters and files visible for review; retain existing release workflows and symmetric authorization-server promotion.
- Provide actionable guidance for updates such as host-managed K3s that cannot be represented as repository changes. Do not commit, push, dispatch workflows, publish images or modify live clusters.

## Capabilities

### New Capabilities

- `update-inventory-and-discovery`: Discover repository and cluster components, assess coverage and report current state and available updates using ecosystem-specific strategies.
- `update-local-preparation`: Safely prepare reviewable, scoped local updates for GitOps configuration and custom-project dependencies.

### Modified Capabilities

None.

## Impact

A new Python module, configuration, tests and operator documentation will interact read-only with configured Kubernetes contexts, registries, Helm repositories and release sources, and invoke ecosystem tools where appropriate. Local preparation can modify declared repository files, but existing cluster reconciliation, secrets and release pipelines remain under their existing controls. Complete coverage depends on source availability, tool installation, permissions and strategy support; gaps must be reported rather than hidden.
