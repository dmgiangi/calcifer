## Why

Home authentication currently keeps OAuth grants and browser sessions in process memory, so pod restarts lose state and the two identity instances cannot share active flows. Home should normally use durable state hosted in Cloud while remaining able to authenticate the single homelab administrator during an Internet or private-transit outage, then recover automatically without operator intervention.

## What Changes

- Add a dedicated, persistent single-replica Redis service in `calcifer-cloud` as the authoritative store for OAuth authorization state and browser sessions while connectivity is healthy.
- Allow the Home authorization server to reach only that Redis endpoint through the existing WireGuard private transit.
- Add an explicit `CONNECTED`, `ISOLATED`, and `RECOVERING` state machine with hysteresis and request-level fencing; Home enters isolation only between requests and never retries an ambiguous Redis operation against local memory.
- Give Home a fresh process-local store during isolation so LAN password login remains available without Internet; isolated state is never merged into Redis.
- Automatically recover after stable Redis connectivity returns by acquiring a lease, atomically advancing a Redis generation, discarding Home-local state, and requiring affected browser flows to authenticate again.
- Keep Cloud fail-closed when Redis is unavailable and expose temporary recovery unavailability rather than creating a second autonomous writer.
- Persist connected browser sessions in Redis and make `https://auth.calcifer.tech` the only supported stateful OAuth hostname and Google callback URI. Retire the `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` OAuth endpoints and callbacks. Google login remains unavailable when Home has no Internet, while local password login remains available.
- Add health, metrics, logs, alerts, and acceptance tests for mode transitions, Redis availability, recovery, interrupted OAuth flows, and tunnel loss without exposing credentials or token material.
- **BREAKING**: OAuth flows and browser sessions active before isolation or generation recovery are invalidated and must restart; the former guarantee of always process-local authorization state is removed.

## Capabilities

### New Capabilities
- `resilient-authorization-state`: Defines authoritative Redis state, isolated Home operation, automatic generation-based recovery, fencing, and failure behavior for OAuth grants and browser sessions.

### Modified Capabilities
- `cluster-identity-bridge`: Replaces always-local OAuth state with shared connected state and explicitly isolated Home state while preserving one issuer and portable short-lived JWTs.
- `cluster-identity-routing`: Defines canonical-only endpoint behavior through connected, isolated, and recovering modes and the Google callback behavior under split-horizon DNS.
- `grafana-authorization-server-integration`: Changes Grafana OIDC endpoints to use the canonical authorization hostname instead of an endpoint-specific hostname.
- `cloud-home-private-transit`: Adds a narrowly permitted Home-to-Cloud Redis transport without joining Kubernetes Pod or Service networks.
- `authorization-server-observability`: Adds state-mode, Redis, transition, and recovery telemetry plus outage and automatic-recovery acceptance coverage.

## Impact

- Application: `authorization-server` storage, session, health, configuration, and tests.
- Cloud infrastructure: dedicated Redis workload, PVC/AOF configuration, private TCP exposure, ACL Secret, NetworkPolicy, monitoring, and alerts.
- Home infrastructure: Redis connection configuration, WireGuard/firewall permissions, local fallback behavior, and mode-aware probes.
- Identity behavior: in-flight flows can fail during a transition; Redis reset does not revoke already issued JWTs, so access tokens remain short-lived and refresh tokens remain disabled in this change.
- Dependencies: Spring Redis and Spring Session support plus a Redis-backed `OAuth2AuthorizationService`; any new dependency and its version will be selected through the project package manager during implementation.