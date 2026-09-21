# Tasks

## 1. Preflight and migration inventory

- [x] 1.1 Inventory every manifest, Service FQDN, NetworkPolicy selector, Traefik reference, Flux health check, observability query, backup resource, and operational command that names `home-assistant`, `mqtt`, `zigbee2mqtt`, `voice-assistant`, or `homepage`; verify the inventory has no unexplained repository matches.
- [x] 1.2 Render the current `calcifer-cloud` and `calcifer-home` Kustomize trees and record a clean baseline; verify both renders succeed before migration edits.
- [x] 1.3 Verify Home Assistant and Zigbee2MQTT backup CronJobs, Secrets, Restic repositories, restricted Cloud proxy, and WireGuard path are healthy without printing credentials; verify a recent successful snapshot exists for each application.
- [x] 1.4 Record the current workload, route, PVC, certificate, and Flux readiness state in both clusters so rollback targets are explicit; verify all source resources expected by the migration are present.

## 2. Prepare domain namespaces and destination resources

- [x] 2.1 Add declarative `home-automation` and `web` Namespace resources in the appropriate cluster trees while preserving every platform namespace; verify rendered output contains each domain namespace exactly once.
- [x] 2.2 Recompose Home Assistant, Mosquitto, Zigbee2MQTT, and voice-assistant manifests under `home-automation` with stateful Deployments initially inactive and new PVCs empty; verify no destination pod can start before restore.
- [x] 2.3 Re-encrypt destination SOPS Secret manifests with their new namespace metadata and preserve Secret key contracts; verify SOPS decryption/rendering succeeds without exposing values.
- [x] 2.4 Rewrite destination Service DNS, ConfigMap, Certificate, CronJob, NetworkPolicy, and same-namespace object references for `home-automation`; verify no destination resource depends on `mosquitto.mqtt.svc.cluster.local` or another removed namespace.
- [x] 2.5 Recompose Homepage in `web` on both clusters and Cloud Home Assistant/Zigbee2MQTT edge proxy resources in Cloud `web`, keeping canonical routes inactive during prepare; verify there are no duplicate active Host rules.
- [x] 2.6 Add temporary Home Assistant and Zigbee2MQTT restore Job manifests or templates using the pinned Restic images, destination backup Secrets, staging volumes, replacement PVCs, and integrity checks; verify they cannot select an unrecorded snapshot or overwrite source PVCs.
- [x] 2.7 Update Flux/Kustomize composition and health checks for the prepare stage while retaining source namespaces, workloads, routes, and PVCs; verify Flux pruning cannot delete rollback state in this stage.
- [x] 2.8 Render both prepare-stage cluster trees, run repository validation, and inspect namespace/resource uniqueness; verify all checks pass before reconciliation.

## 3. Reconcile and validate the prepare stage

- [x] 3.1 Commit and push the prepare stage to the Flux-followed branch, reconcile both clusters, and verify the Git source and affected Kustomizations reach the expected revision and `Ready=True`.
- [x] 3.2 Verify `home-automation` and `web` contain the expected inactive resources, empty replacement PVCs, destination Secrets, and restore prerequisites while all source workloads and canonical routes remain active.
- [x] 3.3 Verify platform namespaces and unrelated applications remain ready and that no canonical hostname, certificate, DNS record, or Cloud-to-Home route changed during prepare.

## 4. Freeze source workloads and create final backups

- [x] 4.1 Suspend the Home Assistant and Zigbee2MQTT scheduled CronJobs and verify no backup Job is running before the freeze begins.
- [x] 4.2 Stop Home Assistant and Zigbee2MQTT writers, then Mosquitto and dependent voice services, and verify their pods have terminated while source PVCs remain bound.
- [x] 4.3 Trigger a final Home Assistant backup from the source CronJob, verify successful Restic completion and SQLite integrity, and record the exact selected snapshot ID without exposing credentials.
- [x] 4.4 Trigger a final Zigbee2MQTT backup from the source CronJob, verify successful Restic completion, repository check, and snapshot checksum metadata, and record the exact selected snapshot ID without exposing credentials.
- [x] 4.5 Confirm both backup schedules remain suspended and source PVCs/namespaces are unchanged; verify the migration has a stable recovery point before restore.

## 5. Restore application state into `home-automation`

- [x] 5.1 Pin the temporary restore Jobs to the recorded snapshot IDs, reconcile them in `home-automation`, and verify each Job completes without resolving an arbitrary `latest` snapshot.
- [x] 5.2 Verify the Home Assistant replacement PVC contains the restored configuration and `.storage` tree and that `PRAGMA integrity_check` returns `ok` for the restored SQLite database.
- [x] 5.3 Verify the Zigbee2MQTT replacement PVC checksum file and restored configuration, coordinator, network, and paired-device state all pass validation.
- [x] 5.4 Verify the Mosquitto replacement PVC is intentionally empty, its credential Secret and declarative configuration are present, and no source broker data was copied accidentally.
- [x] 5.5 Preserve completed restore Job status and source rollback resources until cutover validation finishes; verify neither old namespace is eligible for pruning.

## 6. Perform the ordered cutover

- [x] 6.1 Activate Mosquitto in `home-automation`, reconcile, and verify authenticated readiness, rejected anonymous access, ClusterIP-only exposure, and persistence on the replacement PVC.
- [x] 6.2 Activate restored Zigbee2MQTT after Mosquitto is ready, reconcile, and verify exclusive coordinator access, MQTT connectivity, paired devices, state publication, and discovery republishing.
- [x] 6.3 Activate voice-assistant services and restored Home Assistant after their dependencies are ready, reconcile, and verify Home Assistant starts from restored state and reaches Mosquitto and speech endpoints in `home-automation`.
- [x] 6.4 Replace Home canonical ingress resources and Homepage with their destination namespace versions in one controlled route cutover; verify each hostname has exactly one effective route per cluster and keeps its existing certificate and URL behavior.
- [x] 6.5 Replace Cloud Home Assistant and Zigbee2MQTT edge proxies with the `web` resources and verify HTTPS forwarding over WireGuard preserves Host, SNI validation, WebSockets, and authorization behavior.
- [x] 6.6 Update namespace-sensitive Grafana queries, alerts, labels, NetworkPolicies, Flux health checks, and remaining operational references; verify repository searches find no stale active dependency on removed workload namespaces.

## 7. Validate backups and application behavior

- [x] 7.1 Resume Home Assistant and Zigbee2MQTT CronJobs in `home-automation`, trigger one manual backup of each restored application, and verify new snapshots and retention processing complete through the restricted proxy.
- [x] 7.2 Validate Home Assistant UI, OIDC and local fallback, automations, integrations, history, MQTT entities, and `/api/websocket` through both LAN and public paths.
- [x] 7.3 Validate Zigbee2MQTT frontend authentication, LAN/public routing, coordinator state, device updates, retained discovery topics, and Home Assistant rediscovery without re-pairing devices.
- [x] 7.4 Validate voice STT/TTS behavior, Homepage on both clusters, certificates, split-horizon DNS, Cloud edge routing, and monitoring visibility using the new namespace labels.
- [x] 7.5 Verify both Flux installations and all unrelated platform Kustomizations remain `Ready=True`; if any required check fails, execute the documented rollback using retained source namespaces and PVCs before proceeding.

## 8. Cleanup and documentation

- [ ] 8.1 Remove temporary restore Jobs and prepare/cutover-only resources after successful validation; verify steady-state renders contain no migration Job or pinned snapshot ID.
- [ ] 8.2 Remove obsolete `home-assistant`, `mqtt`, `zigbee2mqtt`, `voice-assistant`, and `homepage` namespace resources only where replaced, then reconcile cleanup and verify Flux prunes their old workloads and PVCs without affecting platform namespaces.
- [ ] 8.3 Verify the final namespace inventory, active routes, Services, EndpointSlices, Certificates, NetworkPolicies, PVCs, and backup schedules match the new taxonomy on both clusters.
- [ ] 8.4 Update Home Assistant, Zigbee2MQTT, edge-routing, backup/restore, and cluster operational documentation with new namespaces and recovery commands; verify documented commands reference only steady-state resources.
- [ ] 8.5 Render both final cluster trees, run strict OpenSpec validation and repository checks, and verify the Git working tree contains only the intended implementation and change artifacts.
