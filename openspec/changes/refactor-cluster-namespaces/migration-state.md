# Migration State

## Preflight baseline

- Recorded: 2026-09-21
- Flux branch: `master`
- Source revision on both clusters: `4f8a3b4a21d46571a60f5caf62868b23ad74baf0`
- Cloud render: 43 resources, 273894 bytes
- Home render: 42 resources, 285891 bytes

Both cluster renders succeeded before implementation changes. All Flux Kustomizations reported `Ready=True` at the recorded source revision.

## Source rollback inventory

### calcifer-home

- Active workload namespaces: `home-assistant`, `mqtt`, `zigbee2mqtt`, `voice-assistant`, `homepage`.
- Bound PVCs: `home-assistant-config`, `mosquitto-data`, `zigbee2mqtt-data`.
- Ready Deployments: Home Assistant, Mosquitto, Zigbee2MQTT, Azure STT/TTS, and Homepage.
- Canonical routes and Certificates exist for Home Assistant, Zigbee2MQTT, and Homepage; all Certificates reported ready.

### calcifer-cloud

- Active edge namespaces: `home-assistant`, `zigbee2mqtt`, `homepage`.
- Canonical edge routes and Certificates exist for Home Assistant, Zigbee2MQTT, and Homepage; all Certificates reported ready.

Platform namespaces remained active on both clusters. Home-only `lan-dns` remained active.

## Backup preflight

- Home Assistant target snapshot: `de07d968`, created 2026-09-21 08:58 UTC.
  - Snapshot path: `/snapshot/config`.
  - Backup Job and SQLite-snapshot init container exited successfully.
- Zigbee2MQTT target snapshot: `3197be9a`, created 2026-09-21 08:58 UTC.
  - Snapshot paths: `/snapshot/data` and `/snapshot/SHA256SUMS`.
  - Backup Job, checksum init container, and Restic repository check exited successfully.
- Both backup Secrets exist in their source namespaces.
- Cloud WireGuard reported 1/1 ready and the restricted Azure backup proxy pod was running.

These snapshots prove repository health but are not the final cutover recovery points. Exact final snapshot IDs will be recorded after workload freeze.

## Prepare implementation

- Added `home-automation` on Home and `web` on both clusters while retaining all source and platform namespaces.
- Added six inactive Home destination Deployments and inactive Homepage Deployments with `replicas: 0`.
- Added empty replacement PVC declarations and a prepare-only binding Job because `local-path` uses `WaitForFirstConsumer`; the Job verifies all three mounts are empty and exits without starting an application workload.
- Relocated eight SOPS Secrets, regenerated their MACs for the destination namespace metadata, and verified their key contracts and decryption.
- Rewrote the encrypted Zigbee2MQTT broker endpoint to `mosquitto.home-automation.svc.cluster.local`.
- Added isolated, non-rendered restore Job templates that reject an unset, `latest`, or non-hexadecimal snapshot ID and validate staged data before copying to replacement PVCs.
- Kept every destination canonical IngressRoute and DNS endpoint outside the prepare composition; source routes remain active.
- Prepare renders contain 99 Home resources and 75 Cloud resources when combined with the existing active Homepage overlays. Resource identities are unique, destination routes are absent, and all source namespaces remain rendered.

## Prepare reconciliation

- Prepare commit: `87c7b95b55b319cebe99ec58130245e456c6b295`.
- `home-apps` and `cloud-apps` applied the prepare revision and every Flux Kustomization reported `Ready=True`.
- All three Home replacement PVCs reached `Bound`; the prepare-only binding Job completed successfully after verifying empty mounts.
- Six Home destination Deployments and both Homepage destination Deployments remained at zero replicas.
- All source Deployments remained ready, and each canonical Home Assistant, Zigbee2MQTT, and Homepage hostname had exactly one active route per cluster.
- All source and destination Certificates were ready; destination DNS endpoints and canonical IngressRoutes remained absent as intended.
- Platform namespaces and unrelated workloads remained present and ready.

## Namespace-sensitive inventory

The preflight search covered active manifests, documentation, and scripts. It identified workload namespace references, one explicit `mosquitto.mqtt.svc.cluster.local` reference, ten files containing `namespaceSelector`, namespaced Traefik and Certificate resources, Flux health checks, SOPS Secrets, backup resources, and operational commands. These references are tracked by tasks 2.3–2.7, 6.4–6.6, and 8.4.

## Final cutover snapshots

- Home Assistant: pending workload freeze.
- Zigbee2MQTT: pending workload freeze.
