# Proposal

## Why

The Authorization Server currently admits one human identity through a configured email and canonical subject, while assigning its `admin` role in code. Adding users through special-case logic makes identity, permissions, and login methods difficult to review independently. A declarative user catalog will make each person's stable identity, group membership, and permitted login methods explicit.

## What Changes

- Define a declarative user catalog for verified Google identities, stable canonical subjects, assigned groups, and allowed authentication methods.
- Permit Google-only users to sign in through Google while explicitly denying their use of local password authentication, even when local password login is enabled for another account.
- Derive group membership claims from the catalog and preserve the existing `roles` claim contract for current OIDC integrations.
- Let OIDC clients specify which groups may authorize; clients without an explicit policy remain restricted to the existing administrator group.
- Keep the Cloud and Home identity catalogs and resulting subjects/group claims equivalent. Do not add public signup, a user database, or a management UI.

## Capabilities

### New Capabilities
- `authorization-user-groups`: Declarative human-user identities, authentication-method eligibility, group assignment, and corresponding identity claims.

### Modified Capabilities
- `cluster-identity-bridge`: Map each accepted authentication method to the same configured subject and groups across both clusters; enforce per-user login-method eligibility and expose group claims without breaking existing roles.
- `declarative-oidc-clients`: Allow interactive clients to authorize configured groups while preserving administrator-only access as the default for existing clients.

## Impact

Changes are expected in `authorization-server/` user configuration, authentication, token/UserInfo claim generation, and tests, plus the shared external identity/client configuration used by both cluster deployments. Existing issuer, client credentials, administrator subject, local-login secret handling, and `roles` consumers must remain compatible. Rage Quit itself is not implemented by this change; its later change will consume the user/group capability.
