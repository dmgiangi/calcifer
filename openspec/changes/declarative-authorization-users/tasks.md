# Tasks

## 1. User and group catalog

- [ ] 1.1 Add declarative user/group/authentication-method properties with validation for unique subjects, known groups, and supported methods; verify with configuration-binding and validation tests.
- [ ] 1.2 Configure the same stable administrator identity and existing `admin` membership for Cloud and Home, and verify rendered configuration contains equivalent non-secret user/group metadata.

## 2. Authentication-method enforcement

- [ ] 2.1 Resolve verified Google identities through the user catalog and apply the configured canonical subject and groups; verify allowed, unverified, and unknown-email cases in OIDC user-service tests.
- [ ] 2.2 Restrict local password authentication to catalog users explicitly permitting `password`, while retaining the global enable switch and secret hash; verify a Google-only user's password attempt is denied even when password login is enabled for another account.

## 3. Group claims and client policy

- [ ] 3.1 Emit catalog-derived `groups` in interactive tokens and UserInfo while preserving current `roles` values and client-credentials claims; verify claim customizer and UserInfo tests.
- [ ] 3.2 Add declarative group restrictions for interactive clients with an administrator-only default and startup validation of unknown groups; verify admitted/denied users and unchanged client-credentials behavior.

## 4. Compatibility and integration verification

- [ ] 4.1 Run the Authorization Server test suite and verify existing issuer, administrator subject/role, password-hash, and client-credentials tests continue to pass.
- [ ] 4.2 Validate Cloud and Home rendered configuration and run OpenSpec strict validation; confirm both sides apply equivalent identity/group policy and no application deployment is added by this change.
