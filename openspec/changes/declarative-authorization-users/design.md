# Design

## Context

See `proposal.md` for motivation and the spec deltas for required behavior. Today the Authorization Server has one configured Google email and canonical subject, assigns `admin` in code, and optionally exposes one local administrator password login. OIDC clients are already declared in externally mounted YAML. The same issuer and equivalent identity/client configuration are used by Cloud and Home.

## Goals / Non-Goals

**Goals:**
- Keep human identities, stable subjects, groups, and permitted interactive login methods explicit and reviewable in configuration.
- Enforce the same catalog and client group policy in both authorization-server instances.
- Preserve the administrator's existing subject, `roles` claim, local-password secret handling, and machine-client behavior.

**Non-Goals:**
- Public registration, a database-backed user directory, or a web UI for editing users/groups.
- Changing Google credentials, the issuer, OAuth grants, application-specific databases, or Rage Quit application behavior in this change.

## Decisions

### Use the existing external identity configuration for the user catalog

Extend the declarative configuration already mounted for OIDC clients with an `identity.users` catalog and the related group policy. Keep non-secret identity metadata in the shared external YAML/ConfigMap configuration and continue sourcing credentials and password hashes only from existing secret mechanisms. This avoids introducing another service or a second user store; Cloud and Home consume equivalent configuration.

Alternatives considered: a database or separate identity service would add runtime and operational dependencies without a need for self-service user management. Hardcoding participant emails and roles in Java is rejected because it recreates the current limitation.

### Keep identity, groups, and authentication methods as separate fields

Each user entry maps a verified Google email to a canonical subject and explicit groups, and separately lists permitted interactive authentication methods such as `google` and `password`. Google's provider response establishes the verified login identity but never supplies application groups. The local password provider must resolve the same configured user and reject authentication unless `password` is explicitly allowed; the global local-login switch and configured secret remain additional gates. A Google-only user's lack of `password` permission is authoritative even if another account can use local login.

Alternatives considered: inferring a role from email or from whether a user is the administrator is rejected because it conflates identity with authorization. Treating a global password-login switch as permission for every user is rejected because it would defeat Google-only accounts.

### Publish trusted groups while retaining the legacy role contract

Use `groups` as the explicit membership claim for interactive identities in OIDC tokens and UserInfo. Continue emitting the established `roles` claim for compatibility; existing administrator membership continues to produce `admin`. Only catalog-assigned groups are eligible for those claims. Client group restrictions are evaluated against this trusted membership, with `admin` as the default policy for existing interactive clients.

Alternatives considered: replacing `roles` outright risks breaking existing clients; relying only on per-application email allowlists duplicates identity policy outside the Authorization Server.

### Validate policy before serving authorizations

Reject duplicate subjects, unknown groups/methods, invalid group references, and a local-password configuration that targets a user not permitted to authenticate with `password`. Enforce user method eligibility at authentication and group eligibility for interactive clients before authorization is granted; leave client-credentials behavior on its existing independent path.

## Risks / Trade-offs

- [A configuration error can grant an unintended group] → Require explicit group assignment, validate references and defaults at startup, and test positive and negative authorization cases.
- [Cloud and Home can drift] → Keep the catalog and client configuration shared/equivalent and verify rendered configuration for both clusters before rollout.
- [Legacy clients may depend only on `roles`] → Preserve the existing administrator role claim and add `groups` without removing existing claims.
- [Existing browser sessions may predate the new identity metadata] → Fail closed when membership or authentication-method eligibility cannot be established; require a new login rather than infer groups.

## Migration Plan

1. Add contract and tests for the user catalog, per-user method enforcement, token/UserInfo groups, client group policies, and legacy claims.
2. Configure the existing administrator with the same canonical subject and `admin` group, preserving password eligibility only where local login is enabled. Add further users explicitly as Google-only unless password access is intentionally granted.
3. Configure existing interactive clients with the administrator group as their effective default; configure new group access only for intended clients. Keep machine clients unchanged.
4. Roll out the matching authorization-server configuration and image to Cloud and Home, then verify Google-only password denial, valid Google login, group claims, and client restrictions on both edges.
5. Roll back by restoring the prior image and matching prior configuration together. Do not leave the two instances on divergent user/group catalogs.

## Open Questions

None. Group names and each user's memberships are deployment configuration values and can be selected during implementation without changing this contract.
