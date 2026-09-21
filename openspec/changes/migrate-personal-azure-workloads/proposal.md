# Proposal

## Why

The personal Azure workloads are currently grouped in `rg-calcifer-westeurope-001` with inconsistent naming and a mixture of global DNS resources, Italy North services, and a West Europe Storage account. The migration should establish one personal subscription, a small and explicit Resource Group boundary, Italy North for regional workloads, and a tested cutover path that preserves backup and restore capability.

## What Changes

- Create one Azure subscription named `sub-personal` in the existing Entra tenant.
- Replace the current Resource Group layout with:
  - `rg-calcifer` for Calcifer/domotics workloads and `calcifer.tech`;
  - `rg-dmgiangi-public` for the public `dmgiangi.dev` DNS zone.
- Recreate the Blob Storage account in Italy North with a descriptive name, private containers, and access restricted to the static public egress IP of `calcifer-cloud`.
- Copy all required Blob data to the new account, using an initial synchronization while backups remain active and a final synchronization during a short backup cutover window.
- Recreate or rename the Italy North Speech resource and update its consumers.
- Do not recreate `vnet01`: it is not required for the Storage firewall rule and currently has no Storage network association, private endpoint, or peering.
- Move both DNS zones to their target Resource Groups and recreate the required DNS Zone Contributor assignments.
- Update Kubernetes manifests, backup scripts, encrypted secret payloads, and operational documentation with the new subscription, Resource Group, Storage, and Speech identifiers without exposing credentials.
- Verify data integrity, backup scheduling, representative restores, Velero recovery, DNS-01 certificate issuance, Storage firewall behavior, and Speech connectivity before decommissioning the old resources.
- **BREAKING**: Azure resource IDs, subscription IDs, Resource Group names, Storage account names, and Speech endpoint references will change.

## Capabilities

### New Capabilities

- `personal-azure-workload-migration`: Defines the target personal Azure topology, controlled data migration, backup cutover, rollback retention, and post-migration acceptance checks.

### Modified Capabilities

- `observability-blob-storage`: Update the designated Storage account and network restriction requirements for the Italy North replacement account.
- `observability-backup-recovery`: Ensure Velero backup and restore validation continues to use the migrated Blob location.
- `home-assistant-backup`: Preserve scheduled backup, static Cloud egress, credential protection, and restoreability while changing the Azure storage target.
- `home-certificate-issuance`: Update Azure DNS subscription and Resource Group targeting after the DNS zone moves.

## Impact

- Azure subscription, Resource Groups, DNS zones, Storage account, Speech account, firewall rules, and RBAC assignments.
- Kubernetes manifests under `clusters/calcifer-home` and `clusters/calcifer-cloud`.
- Backup provisioning scripts, SOPS-encrypted Secret manifests, and operational documentation.
- Flux reconciliation and cert-manager DNS-01 issuance.
- Velero/Kopia backup and restore workflows.
- No new external dependency is planned; Azure CLI/AzCopy and the existing repository tooling will be used.
