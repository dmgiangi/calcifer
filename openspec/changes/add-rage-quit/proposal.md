# Proposal

## Why

Three friends need a small private application that records both cigarettes smoked and occasions when they resisted smoking. Timestamped records, understandable scores, and shared charts should make progress visible without requiring a separate database service or a complex frontend.

## What Changes

- Add `rage-quit`, a Java 25 / Spring Boot application with a mobile-friendly Italian interface, lightweight JavaScript, and SVG charts.
- Admit exactly `dem.gianluigi@gmail.com`, `pugliens@gmail.com`, and `frevadiscor@gmail.com` through verified Google identities federated by the existing authorization server.
- Record individual smoking and resistance events with timestamps; allow owners to backdate, correct, and delete their records.
- Show time since the last cigarette, mean and maximum intervals, daily counts, and daily and cumulative comparison charts.
- Keep scores separate: one penalty point per cigarette and one resistance point per resisted occasion. Rank the group by ascending cumulative cigarette count, preserving ties; resistance points never cancel smoking penalties.
- Use `Europe/Rome` calendar days, one shared tracking start date, and explicit zero-smoking confirmations distinct from missing reports.
- Persist events in SQLite on a Kubernetes PVC, with a documented verified backup and restore procedure.
- Deliver the application only to `calcifer-cloud` at `https://rage-quit.calcifer.tech`, including immutable image publication, Flux resources, HTTPS, health checks, and encrypted client secrets.
- Extend the shared authorization server with stable participant identities and client-level restrictions. Preserve administrator and machine-client behavior; the two other participants must not gain access to existing applications.

## Capabilities

### New Capabilities

- `rage-quit-access`: Three-user Google/OIDC admission, private sessions, and owner-only mutations.
- `rage-quit-tracking`: Timestamped smoking/resistance events, zero-smoking declarations, and durable transactional storage.
- `rage-quit-insights`: Daily scores, cumulative ranking, intervals, and charts with honest missing-data semantics.
- `rage-quit-cloud-delivery`: Cloud-only workload, immutable releases, persistent storage, HTTPS, and recovery operations.

### Modified Capabilities

- `cluster-identity-bridge`: Add verified participant identities, distinct canonical subjects and roles, and client-scoped authorization while preserving the existing administrator contract.
- `declarative-oidc-clients`: Declare participant restrictions and Google-only authentication for the Rage Quit client, retaining administrator-only defaults for existing browser clients.

## Impact

- New `rage-quit/` module, release workflow, Cloud application manifests, tests, and operational documentation.
- Shared changes to `authorization-server/`, its identity/client configuration and security tests, including shared-session serialization/native compatibility.
- Rage Quit resources use the existing `web` namespace and are never installed in `calcifer-home`. Minimum shared authorization-server configuration and symmetric release promotion remain necessary to preserve the existing logical issuer contract; other Home workloads are unchanged.
- Dependencies include the existing Spring stack and a SQLite JDBC driver; no external database, SPA framework, public registration, or Kubernetes API data-writing permissions.
- No credentials, signing material, tokens, or personal smoking records are committed to Git. Dependency additions require approval during implementation, and live rollout/restore operations require explicit authorization.
