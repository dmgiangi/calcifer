# Sanitized verification record

**Date:** 2026-09-16
**Change:** `add-resilient-authorization-state`

## Deployment and reconciliation

- Contexts verified: `calcifer-cloud` and `calcifer-home`.
- Git/Flux revision: `master@sha1:1369cc8e` (`release(auth): promote authorization server 0.1.32`).
- Both `authorization-server` Kustomizations reported `Ready=True` at that revision.
- Both authorization-server Deployments completed rollout successfully.

## Health and public contract

- Cloud and Home readiness probes returned `UP`.
- OIDC discovery on both paths reported issuer `https://auth.calcifer.tech`.
- JWKS was available on both paths.
- Anonymous canonical root returned `302` to `/login`; `/login` returned `200`.
- User verified password and Google authentication, canonical callback routing, authorization code/PKCE, client credentials, Grafana OIDC, resource-server JWT validation, and direct-login SSO from both network locations.

## State, transit, and recovery

- Connected metrics reported Redis reachable and `CONNECTED` for both roles.
- Dedicated Redis reported healthy availability and AOF persistence; the private Home-to-Cloud Redis TCP path was reachable and undeclared ports were unreachable.
- Controlled Cloud forwarding interruption moved Home from `CONNECTED`, generation `14`, to `ISOLATED` with Redis unreachable. Cloud continuity was retained.
- Removing the temporary rule required no administrative recovery action. Home returned to `CONNECTED`, Redis reachable, generation `15`: exactly one observed generation advance.
- User verified fresh authentication after recovery and the remaining fencing, ambiguous-mutation, session/code invalidation, short-JWT, disabled-refresh-token, and owner/gate-expiry scenarios.

## Observability and diagnostics

- Prometheus exposed role, mode, generation, and Redis reachability metrics.
- No unsupported-feature or serialization errors were found in the inspected authorization-server logs.
- Diagnostics were checked without printing credentials, authorization codes, tokens, session identifiers, passwords, user labels, Redis ACL material, or stored values.
- Redis availability, AOF, memory, rejected-write, connection, and latency alert conditions were verified as healthy; no unexpected alert outcome was present during verification.
