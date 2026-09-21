# Migration record

## Resource references

Recorded on 2026-09-21. This file intentionally contains no credentials.

- Source Storage: `Main Subscription` (`1679edc6-fa8e-4210-9607-7b4dfd048934`), `rg-calcifer-westeurope-001`, `calciferobs`.
- Source Speech: `Main Subscription` (`1679edc6-fa8e-4210-9607-7b4dfd048934`), `rg-calcifer-westeurope-001`, `calcifer-home-speech-it`.
- Source VNet: `Main Subscription` (`1679edc6-fa8e-4210-9607-7b4dfd048934`), `rg-calcifer-westeurope-001`, `vnet01`.
- Target Storage: `sub-personal` (`13339ead-c73d-4697-a75f-25ff6b3d8dd6`), `rg-calcifer`, `stcalciferbackupitn`.
- Target Speech: `sub-personal` (`13339ead-c73d-4697-a75f-25ff6b3d8dd6`), `rg-calcifer`, `speech-calcifer-home-itn`.
- Calcifer DNS: `sub-personal` (`13339ead-c73d-4697-a75f-25ff6b3d8dd6`), `rg-calcifer`, `calcifer.tech`.
- Public DNS: `sub-personal` (`13339ead-c73d-4697-a75f-25ff6b3d8dd6`), `rg-dmgiangi-public`, `dmgiangi.dev`.

## Functional acceptance evidence

The following checks passed on 2026-09-21:

- Home Assistant, Zigbee2MQTT, and Velero wrote new backups to the target account.
- Representative Home Assistant and Zigbee2MQTT restores completed in isolated destinations.
- A targeted Velero restore completed with VictoriaMetrics and VictoriaTraces Ready and VictoriaLogs excluded.
- Authorized Blob access from `calcifer-cloud` succeeded and an unauthorized source was denied.
- A temporary staging DNS-01 certificate for `calcifer.tech` became Ready; existing production certificates were Ready.
- A TTS request from the intended Home workload path succeeded against the target Speech endpoint.
- Target inventories contain the migration baseline plus post-cutover backups.

Operational acceptance was completed on 2026-09-21T09:46:00Z after the backup, restore, DNS-01, Speech, firewall, data-integrity, and GitOps checks passed. The agreed retention duration was zero days, and decommissioning completed successfully.

## Retention gate

- Observation start: 2026-09-21T09:37:14Z, after durable GitOps reconciliation in both clusters.
- Agreed duration: 0 days (immediate decommission authorized by the operator).
- Retention expiry: 2026-09-21T09:46:00Z.
- During the zero-day window, do not delete or repurpose the legacy resources until the final dependency review is complete.
- Confirm new scheduled backups continue to succeed and verify the source Storage account receives no unexpected writes.
- At 2026-09-21T09:43:23Z, Azure Monitor reported zero transactions on `calciferobs` since the observation start; target Home Assistant, Zigbee2MQTT, and Velero backups were successful.

## Rollback procedure

1. Suspend the affected Home backup producers, Cloud Velero schedule, Speech workloads, and relevant Flux Kustomizations.
2. Wait for active backup jobs to finish; retain the target resources and data for investigation.
3. Recover the pre-migration manifests from the repository baseline rather than editing live objects ad hoc.
4. Do not reuse the pre-migration encrypted SAS values: the source Storage primary key was rotated during migration. Generate least-privilege replacement source credentials, update the existing SOPS-encrypted Secret files, and verify no plaintext enters Git or command output.
5. Reapply the source Storage, Speech endpoint, proxy destination, Velero, and cert-manager references. DNS zones already reside in the target subscription; use the preserved DNS inventory only if DNS rollback is independently required.
6. Reconcile the affected Kustomizations, re-enable the old backup targets, and run immediate Home Assistant, Zigbee2MQTT, and Velero backups.
7. Verify source Blob writes, representative restore access, Speech connectivity, DNS-01 issuance, and public DNS before ending rollback mode.

## GitOps gate

The migration changes were committed and pushed. At the observation start, all 10 Cloud and all 9 Home Kustomizations were unsuspended, `Ready=True`, and reconciled to `master@sha1:c20c0443181450496f3ed287ad15519335ecfb87`, with no `ReconciliationFailed` events.

## Decommission review

After the retention expiry, review and delete each legacy resource separately. Reconfirm dependencies immediately before deleting Storage, Speech, `vnet01`, and finally the legacy Resource Group. Never delete the target DNS zones or their active cert-manager role assignments as part of legacy cleanup.

The separate read-only dependency review completed at 2026-09-21T09:43:23Z, before deletion:

- `calciferobs` has no active workload reference; active backup producers point to `stcalciferbackupitn`.
- `calcifer-home-speech-it` has no active workload reference; Home STT/TTS point to `speech-calcifer-home-itn`.
- `vnet01` has no peering, NIC, private endpoint, or attached IP configuration. Its only dependency is a subnet service endpoint and virtual-network rule owned by the legacy Speech account, so delete Speech before the VNet.
- The legacy Resource Group contains only the old Storage account, old Speech account, and `vnet01`; no resource lock prevents their separate deletion.

## Acceptance and decommission result

Acceptance was recorded at 2026-09-21T09:46:00Z with a zero-day retention window. The legacy resources were then deleted separately in dependency order:

1. Storage account `calciferobs`.
2. Speech account `calcifer-home-speech-it`.
3. Virtual network `vnet01`.
4. Resource Group `rg-calcifer-westeurope-001`, after Azure reported zero remaining resources.

The target DNS zones `calcifer.tech` and `dmgiangi.dev` were not deleted; they remain in their approved target Resource Groups. A final Azure inventory confirmed that the legacy Resource Group no longer exists, the target subscription contains only `rg-calcifer` and `rg-dmgiangi-public`, and active repository content contains no legacy resource references.
