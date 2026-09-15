## Context

Cloud and Home each run one authorization-server replica with the same issuer and signing key, but authorization grants and HTTP sessions are currently process-local. The target environment is a single-user homelab: Home must retain LAN password login when Internet or WireGuard is unavailable, automatic recovery is required after connectivity returns, and forcing a fresh login is acceptable. The state involved is security-sensitive and includes single-use authorization codes, so a timeout cannot trigger a retry against another store.

Redis will be a single persistent instance in `calcifer-cloud`, reachable from Home only through the node-level WireGuard path. Registered clients, users, signing material, and policy remain declarative GitOps/SOPS inputs rather than Redis data. `auth.calcifer.tech` is the only supported stateful identity hostname and Google callback; the `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` OAuth profiles are retired.

## Goals / Non-Goals

**Goals:**
- Make Redis authoritative for connected OAuth grants and browser sessions across pod restarts.
- Preserve autonomous Home password login during loss of Redis connectivity.
- Recover automatically and safely after stable connectivity returns.
- Prevent stale-cache replay and cross-store retries after ambiguous outcomes.
- Keep Redis private, least-privileged, observable, and replaceable from GitOps.
- Preserve the canonical issuer and use one canonical Google callback hostname.

**Non-Goals:**
- Transparent continuation of flows across a mode or hostname change.
- Multi-master Redis, local-state synchronization, conflict merging, or zero-downtime recovery.
- Refresh tokens, immediate revocation of already issued JWTs, or multi-user availability guarantees.
- Redis replication to Home or disaster recovery for ephemeral authorization state.
- Routing Kubernetes Pod or Service CIDRs between clusters.

## Decisions

### Separate the Redis control plane from generation-scoped data

Redis uses unversioned control keys for the current generation, recovery lease, and temporary recovery gate. OAuth grants and Spring sessions use generation-scoped keys such as `auth:g<generation>:...`. Advancing the generation atomically makes all older state unreachable immediately; asynchronous `SCAN` plus `UNLINK` cleanup may remove old generations later.

This is chosen over `FLUSHDB` because recovery is idempotent, cannot delete unrelated keys, and does not depend on physical deletion completing. Redis remains dedicated to authorization state, uses a PVC with AOF, an explicit memory limit, `noeviction`, and a namespace-limited ACL. Durability reduces disruption from a pod restart, but the data remains disposable.

### Pin one state route for the complete request

A high-precedence request filter obtains a route before any stateful security processing: Redis plus generation in `CONNECTED`, a Home-local isolation epoch in `ISOLATED`, or rejection in `RECOVERING`. A routing `OAuth2AuthorizationService` and routing Spring `SessionRepository` use that immutable request route. Session identifiers carry their owner and generation/epoch so a cookie from an obsolete route cannot be looked up in another store.

Redis errors after route selection fail the request and record a connectivity failure; they never cause an in-request local retry. Discovery, JWKS, health, and static login assets can remain available during recovery, but endpoints that create, consume, or mutate authorization/session state return temporary unavailability.

This is chosen over cache-aside because an authorization code cached as unused can become replayable after its Redis copy is consumed. It is also chosen over per-operation fallback because a timed-out Redis write may already have committed.

### Give Home a state machine; keep Cloud fail-closed

Home starts `CONNECTED` only after it can read a valid Redis control record. Configurable consecutive-failure and minimum-duration thresholds move it to `ISOLATED` between requests. Entering isolation creates a fresh in-memory authorization store and session repository for a new random epoch; connected state is never copied into it. A Home restart while disconnected creates another epoch and invalidates prior local sessions.

Cloud never creates a local fallback. If Redis is unavailable, stateful Cloud operations fail closed while stateless JWT validation and public metadata remain independent where possible. This prevents two autonomous cells from issuing conflicting state.

### Recover with a leased gate and atomic generation advance

After Home has been isolated, a background coordinator requires a configurable window of successful Redis probes before recovery. It then:

1. acquires a unique Redis recovery lease with TTL;
2. creates an owner-bound recovery gate with TTL;
3. waits a bounded drain interval while both instances reject new stateful requests;
4. runs one atomic script that verifies lease, gate, and expected generation, increments the generation, and removes the gate;
5. discards Home-local state and enters `CONNECTED` on the new generation;
6. schedules cleanup of older generation keys.

If Home disconnects or crashes before the atomic step, the lease and gate expire and Cloud resumes the unchanged generation. Home remains isolated and retries after the next stable window. If the atomic step committed but its response was lost, Home reads the generation and recovery identity before deciding whether to retry. This is chosen over a permanent `RECOVERING` flag because TTL fencing cannot strand Cloud indefinitely.

### Use one canonical hostname for Google login

The Google OAuth client authorizes only `https://auth.calcifer.tech/login/oauth2/code/google`. All application authorization requests use `auth.calcifer.tech`; public DNS routes that hostname to Cloud and Home split-horizon DNS routes it to Home. Spring therefore generates the same canonical callback in both locations, without exposing separate `-cloud` or `-home` OAuth profiles.

The canonical hostname may resolve to a different authorization instance when the client changes network locality. Connected Redis state permits normal cross-instance completion, but no flow is promised to survive a mode or generation change. During complete Home Internet loss, Google login cannot complete and the local password path is used.

### Bound residual token validity

Generation recovery invalidates stored grants and sessions but cannot revoke self-contained JWTs already issued. Authorization-code and client-credentials access tokens therefore keep a short configured lifetime, and this change does not enable refresh tokens. Signing keys are not rotated during recovery.

### Expose mode-aware operations without leaking identity data

Health details and Prometheus metrics report cluster role, state mode, current generation, Redis reachability, transition counts, recovery outcomes, and duration. Readiness reflects whether the instance can serve stateful requests; liveness does not restart a healthy Home process merely because Redis is unavailable. Logs include recovery identifiers but never session IDs, authorization codes, tokens, user labels, or Redis credentials.

### Expose Redis through a narrow node-level private endpoint

Redis remains a ClusterIP service. A dedicated Cloud node-level TCP exposure forwards a selected private port to that service and is accepted only from the WireGuard interface and Home tunnel address; non-tunnel access to the port is dropped. NetworkPolicy and Redis ACL independently restrict the destination and command/key scope. This extends the existing edge transit without advertising either cluster's Kubernetes networks.

## Risks / Trade-offs

- [A transition interrupts active OAuth or Google-login flows] → Return temporary unavailability during recovery and require clients/users to restart authentication.
- [A Redis timeout has an unknown commit outcome] → Pin the request route, fail the request, and never retry it locally.
- [WireGuard flapping repeatedly invalidates sessions] → Use failure hysteresis, a minimum isolation period, and a stable-success window before recovery.
- [Recovery owner crashes while Cloud is gated] → Use owner-bound lease and gate TTLs; no permanent recovery flag is written.
- [Old JWTs remain valid after generation advance] → Keep access tokens short-lived and refresh tokens disabled.
- [Single Redis or Cloud-node failure removes Cloud stateful login] → Persist with AOF/PVC, alert on failure, keep Home isolation available, and accept Cloud fail-closed behavior.
- [Private Redis port is accidentally exposed publicly] → Combine interface firewall rules, source restriction, NetworkPolicy, ACL authentication, and an external negative connectivity test.
- [Custom Redis serialization becomes incompatible] → Version the key/value schema, test round trips for every authorization token type, and deploy compatible readers before writers.

## Migration Plan

1. Add Redis, persistence, ACL Secret, private endpoint, NetworkPolicy, metrics, and alerts in Cloud without connecting either authorization server.
2. Add application dependencies and generation-aware Redis/session implementations behind a configuration flag; retain current in-memory behavior for rollback.
3. Deploy Cloud against Redis first, verify login, token exchange, restart persistence, and rollback while Home remains on the existing release.
4. Deploy Home in connected mode and verify the canonical Google callback plus password login; verify that retired endpoint-specific OAuth hosts are not advertised or accepted.
5. Run acceptance tests that interrupt WireGuard, verify fresh isolated state, reconnect it, observe automatic generation recovery, and confirm forced reauthentication.
6. Enable asynchronous old-generation cleanup after recovery behavior is proven.

Rollback disables resilient state routing and restores the prior process-local service on both clusters. Because the change deliberately treats Redis state as disposable, rollback requires a new login and does not migrate Redis sessions back into memory. Infrastructure removal follows only after both authorization deployments are rolled back.

## Open Questions

No blocking architectural questions remain. Exact timeout values, private TCP port, Redis image version, and memory/PVC sizes are deployment parameters to select from current cluster capacity and validate during implementation.