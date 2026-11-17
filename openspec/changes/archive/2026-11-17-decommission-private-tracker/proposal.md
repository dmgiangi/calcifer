# Proposal

## Why

The private event-tracking service is no longer wanted, but its retirement must not roll back reusable identity and client-authorization safeguards in the shared authorization server. Product-specific references also remain in active configuration and historical OpenSpec artifacts, so the repository should finish with a neutral, maintainable auth contract and no stale product naming.

## What Changes

- Remove the service module, deployment and Cloud reconciliation entry, release workflow, helper scripts, operational documentation, and service-only encrypted client credentials and update-tool mappings.
- Remove the service-specific authorization-server profile, OIDC client registration, group/role configuration, and participant entries that exist only for this service.
- Preserve generic stable-subject identity mapping, verified-provider checks, per-client group/subject and authentication-method restrictions, fail-closed authorization at code issuance and exchange, and shared-session/native compatibility. Existing administrator and machine-client behavior remains unchanged.
- Replace service-specific authorization tests with generic tests that continue to exercise those retained safeguards.
- Remove product-specific naming from current and archived OpenSpec artifacts in the repository, generalizing historical auth scenarios while preserving their reusable requirements. Do not rewrite Git history.
- Keep the persistent volume and its data; no database deletion, live cluster mutation, or credential-value disclosure is part of this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `declarative-oidc-clients`: Document generic subject and authentication-method restrictions alongside group restrictions, with fail-closed enforcement and unchanged machine-client behavior.
- `authorization-user-groups`: Keep non-administrator group examples generic while retaining catalog-derived identity and claims behavior.

## Impact

- Affects the authorization-server configuration and tests, Cloud GitOps resources, CI/release automation, dependency/image update tooling, helper scripts, operational docs, encrypted Secret manifests, and OpenSpec active and archived artifacts.
- Removing the application reconciliation will withdraw its workload through normal GitOps reconciliation. The PVC is explicitly protected from pruning and must remain untouched.
- No new dependencies or changes to existing client APIs, administrator identity, or client-credentials contracts are expected.