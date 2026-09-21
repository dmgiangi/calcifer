# Design

## Context

See `proposal.md` for the motivation and scope. The current Azure inventory contains two global DNS zones, a West Europe Storage account, an Italy North VNet, and an Italy North Speech account in one legacy Resource Group. The Storage account is protected by a deny-by-default firewall and the approved Cloud egress path already provides a stable public source address.

The repository contains Flux-managed Kubernetes configuration for Home and Cloud, SOPS-encrypted credentials, a Home Assistant backup provisioner, and Velero observability backup configuration. Azure resource IDs and names are embedded in several of these integration points, so the migration is a coordinated infrastructure and configuration cutover rather than an isolated Azure move.

## Goals / Non-Goals

**Goals:**

- Establish the `sub-personal` subscription and the two approved Resource Groups.
- Place regional resources in Italy North and keep DNS zones global.
- Replace the West Europe Storage account without losing historical backup data.
- Enforce Blob access through the `calcifer-cloud` static egress IP plus valid authorization.
- Preserve backup schedules, restore capability, DNS-01 issuance, and Speech functionality.
- Keep the old resources available long enough to support rollback and acceptance testing.

**Non-Goals:**

- Introducing Terraform, Pulumi, or another new IaC framework in this change.
- Creating a second personal or shared subscription.
- Creating a new VNet, Private Endpoint, VPN route, or Azure service endpoint for Blob access.
- Changing the public DNS records or redesigning the Cloud/Home WireGuard transit.
- Deleting the legacy resources before the migration acceptance criteria pass.

## Decisions

### Subscription and Resource Group boundaries

Use one subscription, `sub-personal`, with `rg-calcifer` and `rg-dmgiangi-public`. The first group owns Calcifer resources and `calcifer.tech`; the second owns `dmgiangi.dev`. Region names are omitted from Resource Group names because each group can contain both regional and global resources.

**Alternative considered:** several Resource Groups split by service or region. This was rejected because the workloads have one owner and one lifecycle, and the additional RBAC and operational boundaries would add complexity without a current use case.

### Regional resource strategy

Recreate the Storage account in Italy North and copy its data with server-to-server AzCopy synchronization. Recreate the Speech account under its new descriptive name and update consumers. Do not use Resource Mover for the complete migration: the subscription change and Storage region change require separate operations, and resource names cannot generally be changed in place.

**Alternative considered:** move the existing Storage account to the new subscription first. This would preserve its West Europe location and still require a second regional relocation, so direct recreation avoids an unnecessary intermediate state.

### Storage network restriction

Use the Storage firewall with deny-by-default behavior and an allow rule for the static public egress address of `calcifer-cloud`. Keep container access private and require the existing credential mechanisms. Run data synchronization from an approved Cloud execution point so both source and destination firewall rules can authorize the copy.

**Alternative considered:** recreate `vnet01` and attach a service endpoint or Private Endpoint. This was rejected because the current VNet has no Storage association, the requirement is an external VPS public-IP allowlist, and a VNet service endpoint does not replace that external source restriction.

### DNS zone movement

Move `dmgiangi.dev` and `calcifer.tech` as Azure DNS resources into their target Resource Groups in the new subscription, then recreate DNS Zone Contributor assignments for the cert-manager principals. Export and compare record sets before and after the move, and verify public resolution and DNS-01 behavior before retiring the old scope.

**Alternative considered:** recreate the zones manually. A resource move preserves the existing zone and records more directly; manual recreation remains a rollback option if Azure move validation rejects a dependency.

### Backup cutover

Keep backup producers active during the initial and incremental copy. At cutover, pause only the relevant backup schedules, wait for running jobs to finish, synchronize the final delta, update the target references and encrypted credentials, then re-enable schedules and run immediate post-cutover backups.

**Alternative considered:** disable all backup systems before migration. This creates an avoidable protection gap and was rejected. A short final write freeze gives consistency without sacrificing coverage during the preparation phase.

### Validation and rollback

Use object inventory and synchronization results for data validation, then perform representative restores rather than relying only on blob counts. Keep the old account, old Resource Group, and old DNS/RBAC state until the acceptance checklist passes and the agreed retention window expires.

Rollback consists of pausing new schedules, restoring the previous encrypted credentials and references, re-enabling the old backup targets, and retaining the new resources for investigation. DNS rollback uses the preserved pre-migration zone state and is performed only if the post-move DNS validation fails.

## Risks / Trade-offs

- **[Risk]** The new subscription may not be creatable through the current CLI billing context. → Verify billing-profile eligibility before any resource mutation and use the supported subscription-creation workflow without embedding credentials.
- **[Risk]** The desired Storage account name may already be globally allocated. → Check availability before creation and choose a descriptive alternative without adding arbitrary sequence numbers.
- **[Risk]** Backup writes can occur while the initial copy runs. → Perform incremental synchronization and a final controlled backup freeze before cutover.
- **[Risk]** A container may be unused, undocumented, or still required by an old workload. → Inventory recent object activity and repository references for every container, including `loki` and `thanos`, before excluding or deleting anything.
- **[Risk]** Azure DNS moves or RBAC recreation may leave cert-manager temporarily unable to update records. → Apply and verify the target role assignments before switching issuers, and test with the staging issuer first.
- **[Risk]** The Cloud public egress address may change. → Confirm it is static before configuring the firewall and monitor access failures after cutover.
- **[Risk]** Speech credentials or endpoints may be coupled to the old account name. → Recreate the account, rotate or regenerate credentials, update SOPS-encrypted consumers, and run a real connectivity test.
- **[Risk]** Removing the legacy VNet could affect an undocumented dependency. → Inspect Azure resource dependencies and repository references before deletion; defer deletion if any dependency is found.

## Migration Plan

1. Capture a read-only inventory of Azure resources, DNS record sets, RBAC assignments, Storage containers, object counts, and current repository references. Record the source subscription and Resource Group IDs without committing secrets.
2. Confirm the billing profile can create `sub-personal`, create the subscription in the existing tenant, and apply baseline tags, provider registration, budget, and access policy.
3. Create `rg-calcifer` and `rg-dmgiangi-public`.
4. Create the Italy North replacement Storage account and containers with HTTPS-only traffic, private access, deny-by-default network rules, and the Cloud egress allowlist. Create the replacement Speech account and capture its non-secret endpoint identifiers.
5. Generate short-lived migration credentials outside Git and run the initial server-to-server copy from the approved Cloud path. Repeat incremental synchronization while current backups continue running.
6. Move both DNS zones to the new subscription and target Resource Groups. Recreate DNS Zone Contributor assignments and compare all record sets and public nameservers.
7. Update repository configuration, SOPS-encrypted secrets, backup scripts, Velero configuration, egress proxy destinations, Speech consumers, and cert-manager subscription/Resource Group references.
8. Pause backup schedules and wait for active jobs to finish. Run the final Storage synchronization and record a clean completion result.
9. Deploy and reconcile the changed Kubernetes configuration. Re-enable backup schedules and execute immediate Home Assistant, Zigbee2MQTT, and Velero backups against the new account.
10. Run representative Restic/Kopia and Velero restores in safe destinations, verify DNS-01 staging issuance, verify production certificate readiness, test Speech connectivity, and test that an unauthorized Storage source is denied.
11. Observe the new path for the agreed retention window. Keep the old resources untouched during this period and retain a documented rollback point.
12. After acceptance, remove the old Storage account, old Speech account, old DNS scopes, and the unused legacy VNet/resource group in separate, explicitly reviewed operations.

## Open Questions

None. The remaining values that must be discovered during implementation, such as globally available Storage naming and the exact provider/budget settings, do not change the approved architecture or acceptance criteria.
