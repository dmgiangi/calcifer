# Proposal

## Why

Both clusters currently use several workload-specific namespaces that add GitOps, policy, routing, and operational overhead without providing useful security boundaries. Consolidating application resources into domain namespaces will make ownership and cross-namespace dependencies explicit while using the existing backup repositories to protect the state that is costly to recreate.

## What Changes

- **BREAKING** Consolidate Home application workloads into `home-automation`: Home Assistant, Mosquitto, Zigbee2MQTT, and the voice-assistant services move out of their workload-specific namespaces.
- **BREAKING** Consolidate Homepage and Cloud edge proxy resources into `web`, replacing the `homepage`, Cloud `home-assistant`, and Cloud `zigbee2mqtt` workload namespaces.
- Preserve platform boundaries for `flux-system`, `kube-system`, `cert-manager`, `monitoring`, `authorization`, `private-transit`, and Home-only `lan-dns`.
- Update all namespace-sensitive Service DNS names, NetworkPolicies, Traefik resources, certificates, SOPS Secrets, Flux/Kustomize aggregators, observability queries, backup jobs, and operational documentation.
- Suspend scheduled backups, stop stateful clients, create final verified Restic snapshots, restore Home Assistant and Zigbee2MQTT into new PVCs, and retain the old namespaces until validation succeeds.
- Recreate Mosquitto with an empty data PVC; loss of queued messages, retained messages, and persistent MQTT sessions during this planned migration is accepted, while encrypted credentials and declarative configuration are preserved.
- Resume backup schedules in `home-automation` only after the restored workloads pass functional checks, then remove obsolete namespaces and storage through Flux pruning.

## Capabilities

### New Capabilities

- `cluster-namespace-layout`: Defines the platform/domain namespace taxonomy, namespace-sensitive dependency management, and guarded migration lifecycle for consolidating workloads across `calcifer-cloud` and `calcifer-home`.

### Modified Capabilities

- `home-mqtt-broker`: Defines the explicitly accepted one-time loss of transient and persisted broker state during namespace migration while preserving credentials, authenticated service behavior, and normal post-migration persistence.

## Impact

- Affects application manifests and Flux Kustomizations under `clusters/calcifer-cloud` and `clusters/calcifer-home`, plus shared Homepage manifests.
- Changes internal Kubernetes Service addresses and namespace labels used by NetworkPolicy, Grafana queries, and operational commands.
- Requires planned downtime for Home Assistant, Zigbee2MQTT, Mosquitto, and dependent voice services.
- Uses the existing private Azure Restic repositories and restricted WireGuard/Cloud proxy path; it does not introduce new external dependencies.
- Requires one-shot restore jobs and validation gates before obsolete namespaces, PVCs, and routes are pruned.
