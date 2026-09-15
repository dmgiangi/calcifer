## 1. Finalize implementation parameters and dependencies

- [x] 1.1 Record selected Redis image, private TCP port, PVC/memory sizes, state timeouts, hysteresis thresholds, and rollback flag in deployment configuration without adding credentials.
- [x] 1.2 Add the required Spring Redis and Spring Session dependencies through the project build tooling and verify the authorization-server dependency tree.
- [x] 1.3 Add validated configuration properties for cluster role, resilient-state enablement, Redis connection, generation namespace, isolation thresholds, and recovery timing.

## 2. Provision dedicated Cloud Redis

- [x] 2.1 Add the `calcifer-cloud` Redis workload, ClusterIP Service, persistent volume, AOF configuration, memory bound, and `noeviction` policy to the intended Cloud overlay only.
- [x] 2.2 Create the namespace-limited Redis ACL configuration and supply its credential through a SOPS-encrypted Secret without committing plaintext.
- [x] 2.3 Add Redis NetworkPolicy rules that admit only required authorization-server and monitoring traffic.
- [x] 2.4 Add startup, liveness, readiness, disruption, and resource settings that preserve the single-replica persistent deployment semantics.

## 3. Extend private transit for Redis

- [x] 3.1 Add a node-level Cloud TCP forwarding path from the selected WireGuard-only port to the Redis Service without routing Pod or Service CIDRs.
- [x] 3.2 Update Cloud and Home WireGuard/firewall rules to allow only the Home tunnel address to reach the Redis private port and to drop non-tunnel traffic.
- [x] 3.3 Add positive Home-to-Redis and negative public/undeclared-port connectivity checks that do not print credentials or stored values.

## 4. Implement generation-scoped Redis state

- [x] 4.1 Implement and unit-test the Redis control repository for generation reads, recovery lease acquisition, expiring gate ownership, and atomic compare-and-advance behavior.
- [x] 4.2 Implement versioned serialization and generation-scoped indexing for all `OAuth2AuthorizationService` save, remove, ID lookup, and token lookup operations.
- [x] 4.3 Add round-trip and compatibility tests covering authorization codes, access tokens, OIDC attributes, expiry, removal, and single-use code consumption.
- [x] 4.4 Implement asynchronous old-generation cleanup with bounded `SCAN`/`UNLINK` work and tests proving cleanup cannot affect the active generation or control keys.

## 5. Route requests, authorizations, and sessions safely

- [x] 5.1 Implement the immutable request state route and a high-precedence filter that selects connected generation, Home isolation epoch, or recovery rejection before stateful security processing.
- [x] 5.2 Implement the routing `OAuth2AuthorizationService` with Redis and Home-local delegates and tests proving no cross-store retry after Redis errors or mid-request mode changes.
- [x] 5.3 Implement the routing Spring `SessionRepository` with owner/generation-aware identifiers and tests rejecting obsolete Redis generations and isolation epochs.
- [x] 5.4 Map gated or unavailable stateful operations to a bounded temporary-unavailability response while keeping discovery, JWKS, health, and static login resources available.
- [x] 5.5 Replace the existing always-in-memory beans behind the resilient-state feature flag and retain the prior local implementation as an explicit rollback mode.

## 6. Implement automatic isolation and recovery

- [x] 6.1 Implement the Home `CONNECTED` to `ISOLATED` transition with consecutive-failure hysteresis, minimum durations, between-request switching, and a fresh random local epoch.
- [x] 6.2 Implement Cloud fail-closed behavior and prove Cloud never creates local authorization or session state when Redis is unavailable.
- [x] 6.3 Implement the stable-success detector and owner-bound automatic recovery sequence with lease, TTL gate, drain interval, atomic generation advance, and local-state discard.
- [x] 6.4 Add recovery tests for owner crash before commit, lease/gate expiry, lost commit response, repeated probes, reconnect flapping, and exactly-once generation advance per isolation recovery.
- [x] 6.5 Add restart tests proving connected state survives a pod restart while disconnected Home restarts with a new local epoch.

## 7. Configure identity behavior and token bounds

- [x] 7.1 Configure both overlays with the same Redis namespace and role-specific behavior, using the private Redis endpoint only from Home and no Cloud fallback.
- [x] 7.2 Verify short access-token lifetimes and add tests that refresh-token grant and `offline_access` requests remain rejected.
- [ ] 7.3 Configure only `https://auth.calcifer.tech/login/oauth2/code/google`, remove the `-cloud` and `-home` callback registrations and OAuth endpoint profiles, and test canonical login through both split-horizon paths.
- [x] 7.4 Update authorization integration and operations documentation for isolated password login, unavailable offline Google login, automatic forced reauthentication, residual JWT lifetime, and rollback.

## 8. Add observability and operational safeguards

- [x] 8.1 Add mode-aware health contributors and readiness/liveness behavior for connected, isolated, recovering, and Redis-unavailable states.
- [x] 8.2 Add Prometheus metrics and sanitized structured logs for role, mode, generation, Redis reachability, transitions, recovery identity/outcome, and duration.
- [x] 8.3 Add Redis exporter/scraping and alerts for availability, AOF failure, memory pressure, rejected writes, connections, and operation latency.
- [x] 8.4 Add tests or log assertions proving credentials, user labels, session IDs, authorization codes, and tokens are absent from diagnostics.

## 9. Validate rollout and failure behavior

- [x] 9.1 Run authorization-server unit and integration tests plus manifest rendering/validation for both cluster overlays.
- [ ] 9.2 Deploy Redis infrastructure first and verify persistence, ACL enforcement, private reachability, and denied public reachability before connecting authorization servers.
- [ ] 9.3 Roll out Cloud resilient state and verify password/Google login, authorization-code exchange, client credentials, restart persistence, metrics, and rollback mode.
- [ ] 9.4 Roll out Home and verify the canonical Google callback through split-horizon DNS, connected session persistence, LAN password login, and rejection of retired endpoint-specific OAuth hosts.
- [ ] 9.5 Perform the acceptance outage test: interrupt private transit, confirm fresh Home isolation and Cloud continuity, restore connectivity, observe automatic generation recovery, and complete a fresh login without administrative recovery commands.
- [ ] 9.6 Verify an ambiguous connected Redis mutation fails without local retry and that pre-recovery sessions/codes are rejected while unexpired JWTs retain their documented validity.

## 10. Execute final end-to-end verification on both clusters

- [ ] 10.1 Verify that `kubectl` can access both `calcifer-cloud` and `calcifer-home`, that each context targets the intended K3s cluster and node, and that no check uses the wrong cluster.
- [ ] 10.2 Verify Flux health on both contexts with `flux get sources all`, `flux get kustomizations`, and the relevant reconciliation status; confirm the expected Git revision is applied, no Kustomization is stalled, and no reconciliation error is present.
- [ ] 10.3 Verify rendered manifests and live resources for both overlays: authorization-server Deployments/Pods, Services, IngressRoutes, NetworkPolicies, ServiceAccounts, Redis resources, PVC, probes, resource limits, and mode-aware configuration are present only in their intended cluster.
- [ ] 10.4 Verify authorization-server health and observability from both edges: TLS certificate and hostname, OIDC discovery, canonical issuer, JWKS, liveness, readiness, Prometheus metrics, structured logs, and absence of credentials, tokens, authorization codes, session IDs, password data, and user labels from diagnostics.
- [ ] 10.5 Verify canonical-only OAuth routing: `auth.calcifer.tech` resolves to Cloud from the public path and to Home from the LAN path; the canonical Google callback is the only registered callback; `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` are absent from client, Grafana, ingress, and Google configuration and cannot be used as OAuth endpoint profiles.
- [ ] 10.6 Verify connected authentication end to end from both network locations: password login, Google login, authorization-code exchange, PKCE/state validation, client credentials, canonical claims, Grafana OIDC login, and resource-server JWT validation all succeed through the canonical hostname.
- [ ] 10.7 Verify Redis safety and persistence without exposing credentials or values: ACL authentication and key restrictions work, `noeviction` and memory limits are active, AOF/PVC persistence is healthy, restart persistence preserves an unexpired connected flow, generation-scoped keys are used, and old-generation cleanup cannot affect control or active-generation keys.
- [ ] 10.8 From both nodes, verify WireGuard peer identity, recent handshake, tunnel addresses, routes, firewall rules, and listening sockets with `wg`, `ip`, and host firewall tooling; confirm only the declared Home-to-Cloud Redis flow is permitted and no Pod/Service CIDR is routed across the tunnel.
- [ ] 10.9 From an allowed Home path, verify Redis connectivity through the private endpoint; from an Internet host and from undeclared node ports, verify the Redis endpoint is unreachable. Record only reachability and exit status, never credentials, command arguments containing secrets, or stored values.
- [ ] 10.10 Run the controlled outage scenario using node-level SSH and Kubernetes observation: interrupt Home private transit, wait for failure hysteresis, verify Home enters `ISOLATED` with a fresh epoch and retains LAN password login while Google fails closed, verify Cloud remains connected and usable, and confirm liveness/readiness and alerts match the specification.
- [ ] 10.11 Restore private transit and verify automatic recovery without `kubectl apply`, pod restart, Redis flush, manual generation change, or other operator recovery command: observe the stable-success window, recovery lease/gate, temporary stateful unavailability, exactly one generation advance, discarded Home-local state, return to `CONNECTED`, and successful fresh authentication.
- [ ] 10.12 Verify failure and fencing semantics: ambiguous Redis mutations fail without local retry, pre-recovery sessions and authorization codes are rejected, isolated state never appears in Redis, old generations are inaccessible, already issued short-lived JWTs remain valid only until expiry, refresh tokens remain disabled, and recovery owner/gate TTLs release safely after a simulated owner crash.
- [ ] 10.13 Produce a sanitized verification record containing cluster contexts, Git/Flux revisions, resource and probe status, connectivity results, observed state transitions, generation transition count, alert outcomes, and test timestamps; explicitly mark every item in this section passed before closing the change.