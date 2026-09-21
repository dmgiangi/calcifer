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

Operational acceptance is pending both an agreed retention expiry and durable GitOps reconciliation.

## Retention gate

- Observation start: pending durable GitOps reconciliation.
- Agreed duration: pending operator decision.
- Retention expiry: pending operator decision.
- During retention, do not delete or repurpose `calciferobs`, `calcifer-home-speech-it`, `vnet01`, or `rg-calcifer-westeurope-001`.
- Confirm new scheduled backups continue to succeed and verify the source Storage account receives no unexpected writes.

## Rollback procedure

1. Suspend the affected Home backup producers, Cloud Velero schedule, Speech workloads, and relevant Flux Kustomizations.
2. Wait for active backup jobs to finish; retain the target resources and data for investigation.
3. Recover the pre-migration manifests from the repository baseline rather than editing live objects ad hoc.
4. Do not reuse the pre-migration encrypted SAS values: the source Storage primary key was rotated during migration. Generate least-privilege replacement source credentials, update the existing SOPS-encrypted Secret files, and verify no plaintext enters Git or command output.
5. Reapply the source Storage, Speech endpoint, proxy destination, Velero, and cert-manager references. DNS zones already reside in the target subscription; use the preserved DNS inventory only if DNS rollback is independently required.
6. Reconcile the affected Kustomizations, re-enable the old backup targets, and run immediate Home Assistant, Zigbee2MQTT, and Velero backups.
7. Verify source Blob writes, representative restore access, Speech connectivity, DNS-01 issuance, and public DNS before ending rollback mode.

## GitOps gate

The migration changes are currently uncommitted and the affected root Kustomizations are suspended in both clusters. Do not resume them until the reviewed changes are committed and available to the Git source revision consumed by Flux; otherwise reconciliation can restore legacy references.

## Decommission review

After the retention expiry, review and delete each legacy resource separately. Reconfirm dependencies immediately before deleting Storage, Speech, `vnet01`, and finally the legacy Resource Group. Never delete the target DNS zones or their active cert-manager role assignments as part of legacy cleanup.
