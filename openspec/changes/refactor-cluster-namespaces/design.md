# Design

## Context

See `proposal.md` for motivation. Application resources currently span workload-specific namespaces on both clusters. Flux reconciles with pruning enabled, Traefik discovers routes cluster-wide, and namespaced objects such as PVCs, Secrets, Certificates, Services, and NetworkPolicies cannot be renamed or moved in place.

Home Assistant and Zigbee2MQTT use `local-path` PVCs and already create encrypted Restic backups in separate private Azure Blob containers through the restricted Cloud proxy. Home Assistant snapshots include a SQLite-safe copy; Zigbee2MQTT snapshots include checksum metadata and coordinator state. Mosquitto has persistent storage but no backup repository, while its credentials and configuration are declarative. Canonical hostnames must remain unchanged.

## Goals / Non-Goals

**Goals:**

- Establish repeatable platform and domain namespace boundaries on both clusters.
- Preserve Home Assistant and Zigbee2MQTT state by restoring final verified backups into replacement PVCs.
- Preserve encrypted credentials, authentication behavior, canonical URLs, private transit, and platform namespaces.
- Keep an operational rollback path until the replacement workloads and routes are validated.
- Make all namespace-sensitive network, observability, backup, and GitOps dependencies explicit.

**Non-Goals:**

- Move or merge platform namespaces such as `monitoring`, `authorization`, or `private-transit`.
- Deliver a zero-downtime stateful migration.
- Introduce a new backup backend or recurring Mosquitto backup capability.
- Preserve Mosquitto messages, retained state, or persistent sessions that existed before cutover.
- Redesign the Cloud-to-Home WireGuard or Traefik routing model.

## Decisions

### 1. Use two domain namespaces

`home-automation` on Home contains Home Assistant, Mosquitto, Zigbee2MQTT, and the Azure speech adapters. `web` on both clusters contains Homepage; on Cloud it also contains the Service, EndpointSlice, IngressRoute, ServersTransport, Middleware, Certificate, and TLS material representing public edge proxies for Home-hosted services.

Platform namespaces remain unchanged because they are meaningful security and lifecycle boundaries. Alternatives considered were keeping one namespace per workload, which retains the current overhead, and placing every application in a single `apps` namespace, which obscures the distinction between Home automation and public web-edge resources.

### 2. Stage, cut over, and clean up in separate reconciliations

The migration uses three GitOps stages:

1. **Prepare:** create domain namespaces, rewritten Secrets/configuration, empty replacement PVCs, and restore support without activating conflicting routes or stateful Deployments.
2. **Cut over:** after operational freeze and restore, replace old routes and activate new workloads in dependency order.
3. **Clean up:** only after validation, remove obsolete namespace manifests and allow Flux to prune old resources and PVCs.

This avoids relying on Flux apply/prune ordering during a direct namespace rename. It also prevents duplicate Traefik routes for the same host. A single-reconciliation move was rejected because pruning could remove the only working state before replacement readiness is known.

### 3. Freeze workloads before selecting final snapshots

Operators first suspend the Home Assistant and Zigbee2MQTT CronJob schedules, then stop Home Assistant and Zigbee2MQTT clients followed by Mosquitto. Manual Jobs created from the suspended CronJob templates produce final snapshots while the source PVCs are quiescent. The selected snapshot IDs are recorded only after Restic completion and application-specific integrity checks succeed.

Although the current Home Assistant snapshot is SQLite-consistent while live, stopping writers gives both applications the same deterministic recovery boundary. Continuing to use an arbitrary `latest` snapshot was rejected because it makes the migration result sensitive to later backup executions.

### 4. Restore into empty replacement PVCs with temporary Jobs

Temporary, declarative restore Jobs in `home-automation` use the existing pinned Restic image, repository URLs, relocated SOPS Secrets, and restricted proxy path. Each Job restores its recorded snapshot into staging storage, validates the tree, and copies only application data into its empty replacement PVC:

- Home Assistant validates expected configuration and `.storage` files plus SQLite `PRAGMA integrity_check`.
- Zigbee2MQTT validates `SHA256SUMS`, configuration, coordinator state, network state, and device database.

Because the Home `local-path` StorageClass uses `WaitForFirstConsumer`, the prepare stage includes a restricted, prepare-only Job that mounts all three replacement PVCs on `calcifer-home`, verifies that they are empty, and exits. This binds the volumes without starting an application workload and allows the wait-enabled Flux Kustomization to become ready before restore.

Restore Jobs are removed after successful validation and are not part of steady-state scheduling. Restoring directly over a running workload or over the source PVC was rejected because it removes rollback and risks mixed state.

### 5. Recreate Mosquitto state, then recover discovery from Zigbee2MQTT

Mosquitto receives a new empty PVC in `home-automation`. Its password file is regenerated from the relocated SOPS Secret. After the broker is ready, restored Zigbee2MQTT starts and republishes retained discovery and availability topics; Home Assistant starts afterward and reconstructs MQTT discovery.

Adding a third Azure Restic repository solely for this one-time migration was rejected because the accepted broker-state loss is limited to queued/retained messages and sessions, while device pairing and configuration live in the protected Zigbee2MQTT and Home Assistant backups.

### 6. Rewrite namespaced resources rather than copying live objects

Manifest namespaces, Kustomize composition, Flux health checks, NetworkPolicies, and Service DNS names are updated in Git. SOPS files whose metadata changes are decrypted and re-encrypted through the existing workflow; live Secrets are never exported into Git. Certificates are recreated in the destination namespace so cert-manager issues destination-local TLS Secrets.

The Mosquitto endpoint becomes `mosquitto.home-automation.svc.cluster.local`. Traefik-origin policies continue to select `kube-system`; same-domain policies prefer pod selectors within `home-automation`. Existing DNS dependencies among `authorization`, `monitoring`, and `private-transit` remain unchanged.

### 7. Validate behavior before destructive cleanup

Validation covers rendered Kustomizations, Flux readiness, PVC population, pod readiness, MQTT authentication, Zigbee coordinator ownership and device state, Home Assistant UI/OIDC/WebSocket/automations, voice services, Homepage, LAN and Cloud routes, certificates, NetworkPolicies, observability labels, and one successful post-cutover backup for each restored application.

Old workloads remain stopped but their namespaces and PVCs remain present until these checks pass. Cleanup is an explicit final commit/reconciliation rather than an automatic consequence of cutover.

## Risks / Trade-offs

- **[Flux prune removes source state too early]** → Use separate prepare, cutover, and cleanup reconciliations; retain old namespace and PVC manifests through validation.
- **[Duplicate canonical Traefik routes produce ambiguous routing]** → Do not activate destination routes until the cutover reconciliation removes or disables source routes.
- **[A final snapshot is incomplete or corrupt]** → Stop writers, require Restic success and application-specific integrity checks, and pin the verified snapshot ID.
- **[Restore credentials or SOPS metadata become invalid after namespace changes]** → Re-encrypt destination Secret manifests and validate decryption through rendered Flux/Kustomize output without exposing values.
- **[Local-path replacement PVCs are empty or on the wrong node]** → Keep replacement workloads stopped until restore completes and retain the existing Home node placement constraints.
- **[Fresh Mosquitto state temporarily removes discovery]** → Start Mosquitto first, restored Zigbee2MQTT second, and Home Assistant last; verify discovery republishing before cleanup.
- **[Migration exceeds the expected outage]** → Keep old PVCs and manifests intact so routes and workloads can be rolled back before cleanup.
- **[Backup schedules prune or supersede the chosen recovery point]** → Suspend schedules during migration and record exact verified snapshot IDs rather than resolving `latest` during restore.

## Migration Plan

1. Render both cluster trees and inventory all resources and references to the old workload namespaces.
2. Add `home-automation` and `web` manifests in a prepare state with destination Secrets, configuration, empty PVCs, NetworkPolicies, and restore support; keep destination workloads and canonical routes inactive.
3. Reconcile the prepare stage and verify destination resources without changing live traffic.
4. Suspend scheduled Home Assistant and Zigbee2MQTT backups; stop Home Assistant and Zigbee2MQTT, then stop Mosquitto and dependent voice workloads.
5. Trigger final manual backup Jobs from the old namespaces, validate their outputs, and record exact Restic snapshot IDs.
6. Run destination restore Jobs, validate staged contents and replacement PVCs, and leave source PVCs untouched.
7. Apply the cutover stage: activate Mosquitto, Zigbee2MQTT, speech services, and Home Assistant in dependency order; move Homepage and canonical ingress/edge resources without duplicate routes.
8. Update and validate cross-namespace DNS, NetworkPolicies, certificates, dashboard/alert filters, Flux health checks, and operational documentation.
9. Resume backup CronJobs in `home-automation`, trigger one manual run for each restored application, and verify repository and retention processing.
10. Validate LAN and public access, authentication, WebSockets, MQTT discovery, Zigbee devices, voice behavior, Homepage, and observability.
11. If validation fails, stop destination workloads, restore old routes, and restart source workloads from retained PVCs.
12. If validation succeeds, remove restore Jobs and obsolete namespace resources in a cleanup reconciliation, then confirm Flux readiness and absence of old namespaces.
