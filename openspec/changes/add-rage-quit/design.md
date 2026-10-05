# Design

## Context

See `proposal.md` for motivation and scope. The repository already contains a Java 25 / Spring Boot 4.1.1 authorization server, declarative OIDC clients, SOPS-encrypted Secrets, Traefik routes, cert-manager certificates, and Flux delivery. Cloud stateful workloads use the K3s `local-path` storage class; application-domain resources belong in `web`.

The current `GoogleAdminOidcUserService` accepts only the administrator email and grants `ROLE_ADMIN`. `AuthorizationServerConfiguration.jwtClaimsCustomizer` maps every interactive principal to `user:admin` and the administrator email. Adding emails to that check alone would incorrectly turn all participants into administrators. Cloud and Home share a logical issuer, browser state, and symmetric authorization-server releases, so identity changes require compatibility across both auth instances even though Rage Quit is Cloud-only.

## Goals / Non-Goals

**Goals:**
- Keep operation to one application process and one persistent database file.
- Derive all scores and charts from correctable timestamped records rather than redundant counters.
- Make missing reports visible instead of rewarding apparent abstinence from missing data.
- Deny cross-user writes and prevent participant access to unrelated OIDC clients.
- Preserve existing administrator, machine-client, shared-session, and issuer behavior.

**Non-Goals:**
- Public signup, participant management screens, password accounts in Rage Quit, or social integrations.
- Medical advice, verified abstinence claims, opaque gamification, or resistance points cancelling cigarettes.
- Multiple application replicas, a separate frontend build, native compilation for Rage Quit, or a database service.
- A Home installation of Rage Quit, automatic deployment from this proposal, or a new backup platform.

## Decisions

### 1. Java monolith with a lightweight Italian interface

Create a standalone Maven module under `rage-quit/`, following the existing Java 25 / Spring Boot baseline. Use Spring Security's OIDC client, a conventional JVM container, static HTML/CSS, a small same-origin JavaScript client, and SVG charts with accessible textual summaries. Use JDBC with prepared statements and a SQLite driver rather than an ORM. SQLite JDBC and any other new dependency must be approved and added through supported package/dependency tooling during implementation; no manifests or lockfiles are edited merely to install dependencies.

Alternatives: a SPA adds a build pipeline and browser token handling; a separate language duplicates established security/release conventions; GraalVM native compilation adds SQLite and reflection compatibility work without being necessary for three users.

### 2. A fixed participant registry and client-scoped federation

Keep three preconfigured records with stable subjects and display aliases: `dem.gianluigi@gmail.com` / `user:admin` / `Dem`, `pugliens@gmail.com` / `user:rage-quit:pugliens` / `Pugliens`, and `frevadiscor@gmail.com` / `user:rage-quit:frevadiscor` / `Frevadiscor`. The application admits exactly this registry; there is no runtime signup. Configure the same verified-email-to-subject mapping in the authorization server, preserving legacy single-administrator configuration when no additional participants are configured. Compare emails case-insensitively, but authorize and store ownership by stable subject.

The administrator retains the existing admin identity. Other participants receive the token role `rage-quit-user` (and the corresponding non-admin authenticated authority) and can authorize only the `rage-quit` client. Add declarative client policy for allowed subjects and required authentication source. Existing browser clients default to administrator-only; unknown identities, clients, missing identity metadata, and unverified emails fail closed. Reject unauthorized authorization requests before a code is issued and apply the same policy defensively during code exchange/token issuance. Machine authentication stays separate and unchanged.

Rage Quit uses issuer `https://auth.calcifer.tech`, client/audience `rage-quit`, scopes `openid profile email`, authorization-code with PKCE, `client_secret_basic`, and the exact callback `https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit`. It requires a verified Google-backed authentication, not the local administrator password fallback; existing applications retain that fallback. If the current auth session is local-password-only, require a Google authentication step for Rage Quit rather than silently accepting it. Google-only policy is enforced by the authorization server using trusted authentication provenance, not a user-supplied claim or email string. Rage Quit has no direct Google credentials and never assumes Google and Calcifer subjects are interchangeable.

Tokens and OIDC UserInfo expose the actual participant consistently; the administrator subject/roles and machine-token claims remain backward compatible. Preserve principal metadata through shared Redis session/authorization serialization and native auth-server builds. Pre-change sessions lacking trusted participant/source metadata must reauthenticate where needed, never inherit guessed participant privileges. Two added participants cannot use their Rage Quit session to authorize Grafana, Homepage, Home Assistant, Zigbee2MQTT, or machine clients.

Rage Quit validates standard OIDC issuer, signature, audience, expiry, nonce/state, and its local subject/email allowlist. It stores authentication only in server-side in-memory sessions, using Secure, HttpOnly, SameSite=Lax cookies, CSRF protection, and a POST logout. Restarting the app requires login again but never erases records. Group summaries are visible to all three; raw event history and mutation controls are private to the owner. Tokens, emails, subjects, or activity records are not metric labels or routine log payloads.

### 3. SQLite owns events, not computed scores

Use `/data/rage-quit.sqlite` on a PVC, foreign-key constraints, explicit transactions, a bounded busy timeout, and a serialized write path. Keep durability enabled and start with the default rollback journal rather than introducing WAL-specific backup complexity. Initialize/version the schema using small forward-compatible SQL migrations without a migration framework.

Store an event ID, owner subject, type (`SMOKED` or `RESISTED`), effective UTC instant, creation/update instants, and a version for conflict detection. One event means one cigarette or one resisted occasion. The server assigns the owner and validates event types and timestamps. Backdating is allowed from the group's configured start date to the server's current time; future events and earlier dates are rejected. Retries use a user-scoped idempotency key so one successful submission cannot be counted twice; conflicting reuse is rejected. Optimistic versions prevent stale edits silently overwriting a correction.

Store zero-smoking confirmations separately by owner and Rome date. They are allowed for today and previous tracking days, never future dates or dates containing smoking events. Today is always an in-progress day, even after a zero confirmation; the declaration describes the record at confirmation time, not the remainder of the day. Resistance-only days still require zero confirmation to claim zero smoking. Adding or moving a cigarette into a confirmed-zero date invalidates that confirmation in the same transaction. Deleting the last cigarette does not automatically create a zero declaration.

Persist the common start date in database settings, initialized once from deployment configuration. Reject configuration that silently shifts it after initialization. Counts and interval calculations are derived; edits, deletions, and backdated events therefore update all views without cached score repair.

Alternatives: JSON requires custom locking, atomic replacement, and increasingly complex reporting; a Kubernetes ConfigMap requires API write permissions and conflicts with declarative reconciliation; Redis auth state couples unrelated application data to authentication recovery; PostgreSQL adds a service this workload does not need.

### 4. Time and incomplete-data semantics are explicit

Use UTC instants for storage and elapsed durations. Derive Rome dates with Java `ZoneId.of("Europe/Rome")`, using consecutive local-midnight boundaries, not fixed 24-hour assumptions. Reject ambiguous/nonexistent manually entered local times unless the UI supplies an explicit resolved offset consistent with that zone. Use an injectable clock in tests.

Sort smoking events by effective instant and stable ID. Adjacent smoking events define elapsed gaps across dates; resistances do not restart the clock. The first cigarette has no preceding measured gap. Mean and maximum refer only to completed adjacent-cigarette gaps within the shared tracking period; the current open interval is shown separately, and absent history is unavailable rather than invented. All statistics are explicitly based on self-reported events.

For each day, show cigarette penalties and resistance points separately. A missing smoking report is not confirmed zero. Charts show gaps/unreported states for absent smoking data, not a fabricated zero; a resistance bar can exist alongside an unreported smoking count. Current-day figures are provisional. Users with neither a smoking event nor a zero declaration have no smoking rank, even if they recorded resistances. Once a smoking report exists, rank by recorded cumulative cigarettes over the common period, flag unreported past dates, and label incomplete totals as provisional. Equal cigarette counts share a rank; aliases can stabilize display order but resistance points never break ties.

Show daily smoking/resistance bars, completed-gap progression with completion timestamps, cumulative cigarettes for all participants, and cumulative resistances. Gaps or incompleteness remain visible in cumulative charts. Resistance percentage is `resisted / (smoked + resisted)` within the selected displayed period; no events gives unavailable, not 0% or 100%. This is a fraction of registered episodes, not a clinical success rate. The initial dashboard spans the common start date through today; no periodic reset or independent per-user start is introduced.

### 5. Cloud-only GitOps delivery with recoverable state

Use `clusters/apps/rage-quit/base/` and `overlays/cloud/`, reconciled by a dedicated Cloud Flux Kustomization depending on `authorization-server`, `cert-manager-config`, and the existing `cloud-apps` owner of the `web` namespace. Keep it separate from `cloud-apps` so auth readiness does not impose a new rollout dependency on unrelated applications. Reuse `web` without declaring a second owner for its Namespace; add only the new Flux entry to the Cloud root. No Rage Quit resources or routes are added to the Home root.

Provide Deployment, ClusterIP Service, ConfigMap, SOPS-encrypted client Secret, Traefik HTTPS route/security headers, Certificate, NetworkPolicy, and a 1Gi `ReadWriteOnce` `local-path` PVC. Use one replica and `Recreate`, non-root execution, dropped capabilities, read-only root filesystem, bounded writable `/tmp` plus `/data`, disabled service-account-token mounting, resource requests/limits, and startup/liveness/readiness probes. Readiness checks usable schema and storage without leaking data; auth-provider outages must not produce restart loops. Allow Traefik ingress, authorized probe/monitoring traffic as needed, DNS, and necessary OIDC HTTPS egress. Persistent data is protected from routine Flux pruning; explicitly deleting it remains a destructive operator action.

Register the client and participant policy in shared auth configuration. Mirror required client secrets to both auth overlays under existing issuer-equivalence rules and supply the same secret to the Cloud app. Do not broaden existing applications' subject policy. Auth-code changes continue using the existing symmetric auth release workflow; Rage Quit's own manually dispatched workflow tests/builds a JVM image for linux/amd64, publishes a versioned GHCR artifact, and promotes an immutable digest only in its Cloud overlay. Validate overlays with output suppressed so SOPS or placeholder secrets cannot leak.

DNS must point `rage-quit.calcifer.tech` to the existing Cloud edge. Verify that the existing certificate issuer can issue this hostname before live rollout. Secret provisioning and external DNS changes are documented prerequisites, not credentials embedded in artifacts or automated live writes from this change proposal.

Backup is intentionally operator-run: stop the single writer cleanly, mount the preserved PVC in a maintenance job, create a consistent database copy, perform integrity validation, encrypt the snapshot with operator-managed encryption tooling, and transfer it to a protected operator-controlled off-node destination. Do not copy a live SQLite file unsafely or expose backups over HTTP/Git. Encryption keys are not committed, logged, or passed as command-line values; temporary plaintext copies remain access-restricted and are removed through a documented recoverable cleanup process when no longer needed. Restore stops the app, retains a recoverable copy of current state, decrypts and validates a selected snapshot/schema, restores permissions and data, and checks events and reports before resuming. A backup on the same VPS is not sufficient protection against host loss.

## Risks / Trade-offs

- [Shared auth changes could grant unintended access] → Fail closed per client, preserve legacy defaults, and test participant denial against every existing browser client and both auth paths.
- [Shared sessions or native auth images lose new identity metadata] → Exercise Redis round trips and native tests; reauthenticate ambiguous old sessions and use symmetric auth promotion.
- [Missing/self-reported data distorts rankings] → Show report coverage, unranked empty users, provisional totals, and separate positive/negative counters; never claim verified abstinence.
- [Local-path volume is tied to one VPS] → Single-writer deployment and verified off-node snapshots; explicitly accept downtime during updates and no multi-node availability promise.
- [Future schema changes complicate rollback] → Version the schema, prefer additive compatible migrations, and require verified backup/restore for any incompatible downgrade.
- [JVM resources or SQLite native library extraction fail under hardening] → Test the packaged linux/amd64 image with read-only root, bounded writable temporary storage, and measured memory settings before release.

## Migration Plan

1. Implement and test deny-by-default participant policies before configuring the additional identities or releasing the application.
2. Prepare approved dependencies, immutable images, encrypted secrets, DNS/certificate prerequisites, and the shared start-date configuration without executing live deployment as part of proposal generation.
3. Promote the compatible authorization-server release symmetrically, activate shared participant/client configuration, and verify the existing administrator and machine flows. Pause and revert both auth overlays if access regressions occur.
4. Reconcile the Cloud Rage Quit resources only after auth and TLS prerequisites pass; initialize the persistent start date and schema, then verify all three logins, ownership checks, records, and charts.
5. Produce a verified off-node snapshot and prove restore on disposable storage before calling the deployment operationally ready.
6. Roll back the app by restoring its prior Cloud image digest when schema compatible, retaining the PVC. For an incompatible schema, use the guarded restore procedure. Removing Rage Quit must not prune its database; reverting auth must remain symmetric and preserve other clients.
