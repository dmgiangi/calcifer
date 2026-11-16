# Design

## Context

See `proposal.md` for the motivation and scope. The service is currently represented in source, Cloud GitOps, release tooling, shared authorization configuration, encrypted Secrets, and active/archived OpenSpec artifacts. Its Cloud child reconciliation has pruning enabled, while the persistent claim is explicitly protected from pruning. The authorization server already implements reusable user-catalog, client-policy, shared-state serialization, and native-runtime behavior; some configuration, validation, fixtures, and tests still use service-specific identities and labels.

## Goals / Non-Goals

**Goals:**
- Remove all tracked product-specific references from the repository, including historical OpenSpec files, without rewriting Git history.
- Preserve generic authorization behavior and its regression coverage, including stable subjects, verified identity sources, per-client restrictions, and compatibility for existing browser and machine clients.
- Keep the persistent claim and data untouched and avoid live cluster or Secret-value inspection/writes.

**Non-Goals:**
- Do not add another application, client registration, participant role, or replacement profile.
- Do not delete or restore the persistent database, issue a deployment, modify live clusters, or rewrite repository history.
- Do not weaken or redesign shared identity or authorization policy.

## Decisions

### Remove product configuration, retain generic authorization mechanisms

Delete the app-specific module, workload manifests and Cloud reconciliation entry, app release workflow and updater mappings, helper scripts, operating guides, and encrypted client-secret entries. Remove the app-only client profile and its activation from both authorization-server overlays. Remove the participant catalog entries and non-administrator group/role that are used only by this client; retain the administrator catalog entry and the generic catalog/policy implementation.

The existing generic client policy already supports group restrictions, canonical-subject allowlists, and a required authentication method. Keep those code paths and replace service-named test fixtures with neutral synthetic clients and identities. Preserve tests for authorization denial, reauthentication when a required method is missing, revalidation before code redemption, shared serialized state, native metadata, and unchanged machine-client behavior. Remove the client-ID-specific placeholder-secret exception; retain general required-secret validation for configured clients.

Alternative considered: retain the old profile but leave the client disabled. Rejected because it leaves product references, secret/configuration surface, and dormant access policy in both issuer deployments.

### Preserve generic contracts in canonical specs; scrub historical artifacts

Add generic subject and authentication-method policy behavior to the existing declarative-client capability. Generalize the non-administrator group scenario in the user-group capability. Delete the completed product-specific active change rather than archiving it unchanged, and rewrite the archived authorization-user change's examples and narrative to neutral terms. Do not remove unrelated authorization requirements or modify Git commit history.

Alternative considered: leave archived change files immutable. Rejected because the requested end state includes archived OpenSpec artifacts. Alternative considered: delete all affected authorization history. Rejected because it would discard reusable identity and policy rationale.

### Let GitOps withdraw workloads but preserve storage

Remove the Cloud child reconciliation and its application resources from desired state. The child reconciliation uses pruning; the PVC's explicit prune-disable annotation is the preservation boundary. Do not remove that annotation or the claim manifest. No cluster command, database deletion, restore, or backup operation is included in this repository change.

Alternative considered: delete the PVC with the application. Rejected because it can irreversibly destroy the only persistent copy of private records and was not requested.

### Handle encrypted Secret manifests without exposing values

Remove only the obsolete client-secret keys from encrypted manifests using the repository's SOPS workflow. Keep edits metadata/key-scoped, do not print decrypted values or use plaintext copies, and validate affected manifests with output suppressed. The app-only encrypted Secret is removed with the workload; unrelated authorization secrets stay intact.

## Risks / Trade-offs

- [A missed reference can keep a stale product name or release path alive] → Search all tracked files, including current and archived OpenSpec, with case-insensitive variants; verify the final tracked-file search has no matches.
- [Removing participant catalog entries changes who can sign in to the shared issuer] → Confirm they are not used by other clients in the checked-in configuration; keep existing client policies administrator-only by default and add synthetic tests for generic future participants.
- [Cloud pruning could remove live application resources] → Keep the PVC prune protection, never delete the persistent claim, and leave reconciliation to the normal reviewed GitOps workflow.
- [Secret cleanup could expose or damage unrelated credentials] → Use SOPS-aware, key-scoped edits and suppressed-output validation; never display encrypted or decrypted secret values.
- [Renamed tests could lose security coverage] → Replace app-specific end-to-end fixtures with generic policy tests and run the authorization-server suite, native checks, Kustomize validation, update-tool tests, and strict OpenSpec validation.
- [Updater source and tests already have in-progress work] → Make the mapping removals as narrow edits on top of the current worktree; do not reset, overwrite, or reformat unrelated changes.

## Migration Plan

1. Generalize and validate the OpenSpec deltas before changing implementation.
2. Replace app-specific authorization tests and configuration with generic security coverage; remove the app-specific secret validation branch while preserving general secret checks.
3. Remove the application, Cloud GitOps, CI/release, dependency/image update, script, and operations-documentation references. Remove only associated encrypted secret keys.
4. Generalize affected current and archived OpenSpec artifacts, delete the obsolete active change rather than archiving it, and verify no product-name variants remain in tracked files.
5. Run authorization-server, native-runtime, update-tool, Kustomize, and OpenSpec checks. Review the diff for secrets and confirm the persistent claim and its prune protection remain present.
6. Do not apply the change to a live cluster as part of implementation. After ordinary GitOps reconciliation, the workload is withdrawn while persistent storage remains. Rollback restores source/configuration and may reattach the retained claim; any later data deletion requires a separate explicit decision.