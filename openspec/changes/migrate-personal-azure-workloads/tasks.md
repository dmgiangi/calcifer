# Tasks

## 1. Preflight and migration inventory

- [x] 1.1 Capture a read-only inventory of the source subscription, `rg-calcifer-westeurope-001`, all resource IDs, locations, settings, DNS record sets, nameservers, and RBAC assignments; verify the inventory is stored outside Git or contains no secrets
- [x] 1.2 Confirm billing-profile eligibility and create `sub-personal` in the existing Entra tenant; verify the subscription is active and selected by Azure CLI without changing the source subscription
- [x] 1.3 Confirm the static public egress IP used by `calcifer-cloud` and verify a deny-by-default Storage firewall can allow it; verify the source and destination migration path can run from the approved Cloud location
- [x] 1.4 Inventory all Storage containers, object counts, sizes, recent activity, and repository references, including `backups`, `home-assistant-backups`, `zigbee2mqtt-backups`, `loki`, and `thanos`; record which containers are required
- [x] 1.5 Inspect Azure and repository dependencies of `vnet01`; verify it has no Storage association, private endpoint, peering, or required consumer before scheduling its eventual removal
- [x] 1.6 Confirm the target names `rg-calcifer`, `rg-dmgiangi-public`, the replacement Storage account, and the replacement Speech account are available or document the closest descriptive alternatives without arbitrary sequence suffixes

## 2. Create the target Azure foundation

- [x] 2.1 Create `rg-calcifer` and `rg-dmgiangi-public` in `sub-personal`; verify both Resource Groups exist with the agreed tags and ownership
- [x] 2.2 Create the Italy North replacement Storage account with HTTPS-only traffic, TLS 1.2 or stronger, private containers, deny-by-default network rules, and the approved Cloud egress allowlist; verify the resulting settings with Azure CLI queries
- [x] 2.3 Create the required replacement Storage containers and apply the intended access policies; verify every required source container has a corresponding target container without exposing credentials
- [x] 2.4 Create the replacement Italy North Speech resource and configure its network and SKU settings; verify its endpoint and service health without logging keys or tokens
- [x] 2.5 Move `calcifer.tech` to `rg-calcifer` and `dmgiangi.dev` to `rg-dmgiangi-public` in `sub-personal`; verify record sets and public nameservers before and after the move
- [x] 2.6 Recreate DNS Zone Contributor assignments for the documented cert-manager principals at the new zone scopes; verify role names, scopes, and principal IDs without committing credentials

## 3. Migrate data and perform the controlled cutover

- [x] 3.1 Generate short-lived source and target migration credentials outside Git and run the initial server-to-server AzCopy synchronization from the approved Cloud path; verify a successful exit status and synchronization log
- [x] 3.2 Run incremental synchronizations while the existing Home Assistant, Zigbee2MQTT, and Velero backup schedules remain active; verify source and target inventories converge for every required container
- [x] 3.3 Define and announce the final maintenance window; pause only the relevant backup schedules, wait for active jobs to finish, and verify no backup job remains in progress
- [x] 3.4 Run the final synchronization after the backup freeze and verify its completion, object reconciliation, and presence of the most recent source snapshots in the target account
- [x] 3.5 Update encrypted Azure credentials and target identifiers using the repository's existing SOPS workflow; verify the changed manifests remain encrypted and no secret value appears in Git diff or command output
- [x] 3.6 Re-enable the backup schedules and run immediate Home Assistant, Zigbee2MQTT, and Velero backups; verify each job completes and writes to the replacement Storage account

## 4. Update repository integrations

- [x] 4.1 Update `scripts/provision-home-assistant-backup.py` for the new Storage account, Resource Group, and container target; verify the script's dry-run or static checks contain no legacy target values
- [x] 4.2 Update Home Assistant and Zigbee2MQTT backup manifests and encrypted Secret references under `clusters/calcifer-home`; verify Flux/Kustomize renders the expected target without plaintext credentials
- [x] 4.3 Update Velero's Azure backup storage configuration in `clusters/calcifer-cloud/apps/observability/helmreleases.yaml` and related observability documentation; verify the rendered configuration points only to the replacement account
- [x] 4.4 Update the Cloud Blob egress proxy configuration and any related network policy for the replacement Storage endpoint; verify the proxy still permits only the intended peer and destination
- [x] 4.5 Update Azure Speech endpoint, account, and credential references in the Home voice-assistant deployments and documentation; verify manifests render with the new non-secret endpoint values
- [x] 4.6 Update both Cloud and Home cert-manager ClusterIssuer manifests with the new subscription and Resource Group targeting; verify the Azure DNS credential remains SOPS-encrypted and no old target remains in active manifests
- [x] 4.7 Search active repository content for old Storage account, subscription, and Resource Group references; verify remaining matches are intentional historical documentation or migration records

## 5. Validate functionality and recovery

- [x] 5.1 Render and statically validate all changed Kubernetes manifests and run the repository's applicable tests/checks; verify no YAML, Kustomize, Helm, or script validation errors remain
- [x] 5.2 Verify Blob access from `calcifer-cloud` with valid authorization and verify an unauthorized source is denied; record both outcomes without logging credentials
- [x] 5.3 Perform representative Home Assistant and Zigbee2MQTT backup restores in safe destinations; verify restored configuration/state is usable and historical snapshots remain available
- [x] 5.4 Create and restore a Velero observability backup in a safe namespace or test destination; verify VictoriaMetrics and VictoriaTraces data restores and VictoriaLogs remains excluded as specified
- [x] 5.5 Run the cert-manager staging DNS-01 flow for `calcifer.tech`, then verify production certificate readiness; verify public DNS resolution and issuer events are successful
- [x] 5.6 Test the replacement Speech endpoint from the intended workload path; verify authentication and a representative text-to-speech or speech-to-text request succeed without exposing service credentials
- [x] 5.7 Compare target container inventories, recent backup timestamps, and representative checksums or archive integrity results against the migration baseline; verify the acceptance checklist is complete

## 6. Retention, rollback, and decommissioning

- [ ] 6.1 Keep the old Storage account, old Speech account, and legacy Resource Group unchanged for the agreed observation window; verify new backups continue succeeding and no unexpected writes reach the old account
- [ ] 6.2 Document the rollback procedure and current target/source references; verify restoring the old encrypted credentials and manifests is possible without deleting the new resources
- [ ] 6.3 Record explicit migration acceptance after backup, restore, DNS-01, Speech, firewall, and data-integrity checks pass; verify the acceptance record identifies the retention expiry
- [ ] 6.4 Remove the old Storage, Speech, DNS scopes, and unused `vnet01` only after separate review of each dependency; verify Azure reports successful deletion and no active repository reference remains
- [ ] 6.5 Run a final post-decommission search and Azure inventory; verify only `sub-personal`, `rg-calcifer`, `rg-dmgiangi-public`, and the approved target resources remain
