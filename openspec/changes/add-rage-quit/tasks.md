# Tasks

## 1. Module and dependency setup

- [ ] 1.1 Confirm exact new dependencies/versions with the operator and add them using supported dependency tooling; verify the resolved dependency report includes the approved Spring OIDC/JDBC/SQLite stack without an ORM or frontend framework.
- [ ] 1.2 Create the Java 25 `rage-quit/` module, fixed participant registry, configuration validation, and injectable clock; verify module compilation and tests reject unknown participants, missing issuer/client/storage settings, and invalid start dates.
- [ ] 1.3 Add versioned database initialization/settings and minimal local non-secret configuration; verify startup on a fresh temporary database and fail-closed startup for an incompatible schema or changed persisted start date.

## 2. Shared authorization-server safety

- [ ] 2.1 Extend verified Google admission with the fixed participant-to-canonical-subject map while retaining legacy administrator-only configuration; verify unknown/unverified identities are denied and all three permitted identities resolve correctly without granting new participants admin authorities.
- [ ] 2.2 Add validated declarative client policies for permitted subjects and authentication source, defaulting existing browser clients to administrator-only; verify tests reject unknown subjects/sources and preserve all existing client registrations.
- [ ] 2.3 Enforce client policy before code issuance and defensively during code exchange/token issuance, including reused sessions; verify each additional participant is denied for Grafana, Homepage, Home Assistant, Zigbee2MQTT, machine clients, and any unconfigured client.
- [ ] 2.4 Require trusted Google-backed authentication for Rage Quit, including a new Google step from an administrator password-only session; verify local fallback remains accepted for existing eligible clients and cannot satisfy Rage Quit's policy.
- [ ] 2.5 Emit real subject/email/verified-email and `rage-quit-user` role for additional participants consistently in tokens and UserInfo; verify the existing administrator `user:admin`/`admin` and Grafana API service claims remain unchanged.
- [ ] 2.6 Preserve canonical identity and authentication provenance in shared Redis sessions/authorization state and native runtime metadata; verify Redis round-trip, both auth-edge policy behavior, pre-change-session reauthentication, and focused native compatibility tests pass.
- [ ] 2.7 Declare the Rage Quit client with exact callback, audience/scopes, PKCE and client-secret authentication, and shared participant policy; verify missing secrets, wildcard/unregistered callbacks, incorrect PKCE, unauthorized scopes/grants, and cross-client attempts fail closed.

## 3. Protected application sessions and records

- [ ] 3.1 Implement the application OIDC login/session boundary and subject/email allowlist without direct Google credentials; verify login tests cover the three users, malformed/wrong-issuer/wrong-audience/expired identities, unverified email, login state/nonce, and anonymous API denial.
- [ ] 3.2 Apply secure cookie attributes, CSRF-protected mutations and POST logout; verify cookie/session tests, rejected CSRF requests, invalidation after logout, and absence of tokens in browser storage or application URLs.
- [ ] 3.3 Implement transactional smoking/resistance creation with server-derived ownership, UTC effective time, validation and submission deduplication; verify now/backdated events, future/pre-start rejection, ambiguous Rome times, retry deduplication, conflicting key reuse, and deliberate distinct submissions.
- [ ] 3.4 Implement owner-only event history, type/time corrections, deletion, and version conflicts; verify cross-user access is denied even to the administrator and concurrent/stale corrections cannot overwrite records silently.
- [ ] 3.5 Implement zero-smoking confirmation/withdrawal and transactional invalidation when cigarettes are added or moved; verify resistance-only dates, future/smoking-date rejection, explicit withdrawal, today-in-progress labeling, and deletion not implying confirmed zero.
- [ ] 3.6 Verify transactional persistence and bounded concurrent writes through database integration tests that reopen the same temporary database and retain events, declarations, and group start date after simulated restart.

## 4. Scores and temporal insights

- [ ] 4.1 Implement separate daily cigarette penalties and resistance points using Rome calendar boundaries; verify two cigarettes plus three resistances stays two penalties/three positive points and test both daylight-saving transitions.
- [ ] 4.2 Implement cumulative ascending cigarette ranking with shared ranks, independent resistance totals, unranked empty smoking history, and missing-day/provisional indicators; verify ties, resistance-only users, common-start alignment, and omitted dates.
- [ ] 4.3 Implement since-last and mean/maximum completed smoking gaps, excluding resistance and the open interval; verify zero/one/multiple cigarettes, overnight and daylight-saving gaps, equal timestamps, and backdated corrections/deletions.
- [ ] 4.4 Implement daily/cumulative chart data and recorded-episode resistance percentage; verify missing data differs from confirmed zero, three resistances/one cigarette gives 75 percent, no events gives unavailable, and all aggregates agree after corrections.

## 5. Lightweight Italian interface

- [ ] 5.1 Build mobile-friendly Italian login/dashboard views with separate smoke/resist actions, recent personal history, and date/time correction controls; verify the packaged UI works against authenticated API fixtures without a separate frontend framework/build.
- [ ] 5.2 Add zero-confirmation/withdrawal controls, session logout, submission retry handling and visible validation/conflict feedback; verify the UI distinguishes resistance-only, missing, zero-confirmed, and current in-progress days without double submissions.
- [ ] 5.3 Render accessible daily bars, smoking-gap progression, group cumulative cigarette/resistance charts, scores and coverage indicators using SVG plus text; verify representative empty, tied, incomplete and corrected datasets at mobile and desktop sizes without relying only on color.

## 6. Cloud manifests and immutable release

- [ ] 6.1 Package a non-root JVM image and startup/liveness/readiness behavior; verify a local linux/amd64 container runs with a read-only root, bounded writable temporary/data paths, SQLite native extraction, usable schema/storage, and no auth-outage restart loop.
- [ ] 6.2 Add `clusters/apps/rage-quit/base/` and the Cloud overlay with Deployment, Service, non-secret configuration, 1Gi local-path PVC, hardened runtime, probes, security headers and NetworkPolicy; verify rendered manifests enforce one writer, Recreate updates, no Kubernetes API token, permitted edge/OIDC traffic, and PVC prune protection.
- [ ] 6.3 Add the hostname Certificate and HTTPS route plus a dedicated Cloud Flux entry depending on auth, certificates and the existing web-namespace owner; verify dependency graph has no cycles, no duplicate Namespace ownership, no changed unrelated app dependencies, and no Rage Quit app resources in the Home root.
- [ ] 6.4 Provide secret-provisioning templates/instructions and encrypted-secret references for the Cloud app and both shared auth overlays, without adding plaintext secrets; verify safe placeholder fixtures fail closed, metadata-only checks confirm namespace/key alignment, and suppressed-output Kustomize builds pass for affected roots/overlays.
- [ ] 6.5 Add a manually dispatched Rage Quit release workflow with version validation, tests, container/manifest checks, immutable GHCR publication and Cloud-only digest promotion; verify workflow structure rejects unsafe versions, gates publish/promotion on all checks, and never updates Home application resources or replaces the symmetric auth workflow.

## 7. Recovery and operational documentation

- [ ] 7.1 Document consistent offline backup, integrity checking, snapshot encryption, protected off-node retention and secret-safe encryption-key handling; verify the procedure on a synthetic database without live cluster writes or sensitive output.
- [ ] 7.2 Document guarded restore and schema-compatible image rollback, retaining pre-restore state and the PVC; verify an encrypted synthetic backup restores on disposable storage with matching start date, events, declarations, scores and intervals, and an invalid snapshot never replaces current test state.
- [ ] 7.3 Document the shared start date, reporting semantics, DNS/certificate prerequisites, exact OIDC callback, secret provisioning, auth-first rollout, symmetric auth rollback, Cloud-only app rollout, and post-release acceptance checklist; verify every required prerequisite and permission gate is explicit, with no credential values or real tracking data in Git.

## 8. Integrated acceptance and proposal conformance

- [ ] 8.1 Run focused authorization-server tests plus the Rage Quit test suite and a local end-to-end OIDC integration using synthetic identities; verify all three logins, other-client denials, cross-user protection, event correction, resistance/zero semantics, ranking, intervals, and charts agree with the six change specs.
- [ ] 8.2 Run affected Cloud/Home Kustomize checks with output suppressed, container validation, workflow validation, and strict OpenSpec validation; verify the change remains apply-complete only when implementation checks pass and no Home Rage Quit workload or exposed secret was introduced.
- [ ] 8.3 Deliver an operator-run live acceptance checklist covering three Google logins, unchanged existing apps, HTTPS, restart/update persistence, off-node encrypted backup and disposable restore; verify local rehearsal results are recorded separately from live checks, and explicitly leave deployment, DNS/secret writes, release dispatch, or live restore pending until operator authorization.
