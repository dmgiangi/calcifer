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

- Home Assistant: `90f31d8c`.
  - Created by `home-assistant-backup-final-20260921-immutable` after all source writers stopped.
  - The standard read-only SQLite URI could not create locking state after shutdown, so the successful one-time quiescent Job used `mode=ro&immutable=1`; staging was writable and the source PVC remained read-only.
  - Snapshot init and Restic containers exited successfully, including SQLite integrity validation.
- Zigbee2MQTT: `28937415`.
  - Created by `zigbee2mqtt-backup-final-20260921` after all source writers stopped.
  - Checksum snapshot init, Restic repository check, backup, and retention processing exited successfully.

## Freeze progress

- Source backup schedules were suspended at commit `1c913d01de6a7a84b9e15d6433755d41d86aab60`.
- Both source CronJobs reported `suspend: true`, and neither source namespace contained an active backup Job before writer shutdown.
- Home Assistant and Zigbee2MQTT writers were stopped first at commit `de20beef7268fc49cb3aedbd5c4fec8da609a926`.
- Mosquitto and both voice adapters were stopped second at commit `064db4ccae1c48b3b16466f005acbdb25165e0ab`.
- All source application Deployments reported zero replicas and no active application pods; the three source PVCs remained `Bound`.

## Restore progress

- Zigbee2MQTT restore Job `zigbee2mqtt-restore-28937415` completed successfully, including checksum and critical-state validation before copying to the replacement PVC.
- The first Home Assistant restore attempt restored snapshot `90f31d8c` to staging but its read-only SQLite validation required immutable mode after the quiescent backup; it failed before checking or copying the empty destination PVC.
- The corrected Home Assistant restore Job uses `mode=ro&immutable=1` only for validation of the frozen staged database and has a new immutable Job name.
- Corrected Job `home-assistant-restore-90f31d8c-v2` completed successfully; both restore containers and both Zigbee2MQTT restore containers exited with code zero against the pinned IDs.
- A read-only post-restore probe verified the Home Assistant configuration and `.storage` tree, SQLite integrity, Zigbee2MQTT configuration/device/coordinator/state files, and an empty Mosquitto replacement PVC.
- Mosquitto credential and configuration objects are present, completed restore Jobs remain available, and all source Deployments/namespaces/PVCs remain retained for rollback.

## Cutover progress

- Destination Mosquitto was activated at commit `fc3a063c6de45ca674de0549556f84186120cb33` while the source broker remained stopped.
- Its authenticated readiness probe passed, anonymous publication was rejected, the Service remained ClusterIP-only, and the replacement PVC was `Bound`, mounted, writable, and configured for persistence.
- Restored Zigbee2MQTT was activated at commit `970ae4650946cacf1537b3f6ddaba2eac67e8c86` with the source coordinator workload still stopped.
- Both Zigbee2MQTT containers became ready with zero restarts; logs confirmed MQTT connection, coordinator initialization, network startup, one joined device, MQTT publications, Home Assistant discovery references, and availability publications.
- Before Home Assistant activation, a read-only inspection found two old broker FQDN references in restored `.storage/core.config_entries`; a dedicated cutover Job migrates exactly those references atomically to the `home-automation` Service after JSON validation.
- MQTT endpoint migration Job `home-assistant-mqtt-endpoint-migration-v1` completed successfully; a separate read-only probe confirmed zero old and two new broker FQDN references before Home Assistant startup.
- The first destination Home Assistant startup exposed restored ownership incompatibility: `.storage/http` was owned by UID/GID 1000 with mode `0660`, while the existing hardened init container runs as UID/GID 0 with all capabilities dropped. Home Assistant is returned to zero replicas before a one-time ownership correction; voice services are already ready.
- The first broad ownership Job failed before changing any ownership when recursive `lchown` reached restored `.cache` content. Replacement Job `home-assistant-restore-ownership-v2` limits correction and verification to persistent configuration/state, excludes regenerable `.cache`, and runs as UID/GID 0 with only `CAP_CHOWN`.
- Ownership Job `home-assistant-restore-ownership-v2` completed at revision `8e1379d812612eec2b76bb6e3bc00d21bde5ac15`; the failed `v1` Job was pruned, critical state paths were verified as `0:0`, and `.cache` remained deliberately unchanged.
- Home Assistant restarted successfully at revision `27d04eb6276c1b0aa851c82923fb58300f91f2d6`: all three init containers completed, the main container became ready with zero restarts, and no permission errors were observed.
- A runtime probe verified local HTTP plus TCP reachability to Mosquitto and both Wyoming services. Restored state contained 15 config entries, completed onboarding, a non-empty database, and two migrated MQTT endpoint references; Mosquitto recorded the Home Assistant client connection.
- Destination Homepage workloads were started without routes at revision `aa5687a18a7c91449208ec694ed3432a2cae3ad2` and reached one ready replica plus a ready EndpointSlice in both clusters while source Homepage remained ready.
- Route cutover revision `472dd5f3e2f2c5950d4fabd79832c7bf167474a2` moved Home Assistant and Zigbee2MQTT ingress/DNS to `home-automation` and Homepage ingress/DNS to `web`; source Homepage Deployments were scaled to zero after destination routes were ready.
- Both clusters exposed exactly one effective route for each canonical hostname, destination Homepage Certificates were ready, Home LAN DNS objects were unique in their destination namespaces, and direct Home HTTPS checks returned expected Home Assistant, OAuth redirect, and Homepage redirect responses.
- Cloud edge cutover revision `3d4d1008b824aeaf69ab313fbda7f36b5f246ad1` moved both public edge routes to `web` while retaining source Certificate/Service/EndpointSlice rollback resources.
- Cloud verification found one route per hostname, ready `172.31.255.2` WireGuard endpoints, matching backend SNI names, ready Certificates, preserved Host forwarding, public HTTPS responses of `200` and `302`, and a successful Home Assistant WebSocket upgrade (`101`).
- Namespace-sensitive repository checks found no active old FQDN, dashboard/alert filter, NetworkPolicy, or namespace dependency outside explicitly retained rollback and migration paths; all Flux Kustomizations were ready.

## Post-cutover validation

- Destination backup schedules resumed at revision `678ebe21d10872e11808b64da3e270f5d570d485`; manual Jobs created Home Assistant snapshot `4031f084` and Zigbee2MQTT snapshot `2101588c`, with zero container failures, successful retention, and a clean Zigbee2MQTT repository check.
- Home Assistant exposed its UI over LAN and Cloud, completed public WebSocket upgrade, retained a valid OIDC discovery endpoint plus local `homeassistant` fallback, loaded 15 integrations and 18 MQTT entities, and contained 2,745 historical states plus 5,866 events. No automations existed in the restored registry or history, so there was no automation execution to exercise.
- Zigbee2MQTT retained one joined device without re-pairing, published state/discovery/availability during startup, retained discovery and bridge-state topics, and repopulated the Home Assistant MQTT entity registry.
- An end-to-end Wyoming probe generated 58,240 bytes through Azure TTS and obtained a non-empty Azure STT transcript from that audio.
- Homepage returned its expected authenticated redirect on both LAN and Cloud with ready, zero-restart Pods. VictoriaMetrics and VictoriaLogs contained `home-automation`/`web` label data, all active Deployments and Certificates were ready, all Flux Kustomizations were ready, and split-horizon DNS resolved Home locally and Cloud publicly.
