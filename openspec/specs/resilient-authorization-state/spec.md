# Resilient authorization state

## Purpose

Define persistent connected authorization state, Home isolation, and automatic
generation-fenced recovery for the shared authorization service.

## Requirements

### Requirement: Redis is authoritative for connected authorization state
While the system is connected, Cloud and Home SHALL store OAuth authorizations, authorization codes, consents, and browser sessions in a dedicated persistent Redis instance in Cloud under the active generation. Registered clients, users, policy, and signing keys MUST remain outside Redis and reconstructible from GitOps and SOPS inputs.

#### Scenario: Connected flow survives an authorization-server restart
- **WHEN** an authorization-server pod restarts without changing the active generation
- **THEN** an unexpired browser session or OAuth flow stored in Redis SHALL remain available through the same stateful endpoint profile

#### Scenario: Redis reaches its memory limit
- **WHEN** Redis cannot accept additional authorization state within its configured memory bound
- **THEN** it SHALL reject the write rather than evicting existing authorization or session keys silently

### Requirement: Each request uses exactly one state route
Before processing a stateful request, the authorization server SHALL bind it to one immutable store owner and generation or isolation epoch. A Redis error after binding MUST fail that request and MUST NOT cause the operation to be retried against Home-local state.

#### Scenario: Redis write times out with an ambiguous outcome
- **WHEN** a connected request does not receive confirmation of a Redis mutation
- **THEN** the request SHALL fail without repeating that mutation in the local store

#### Scenario: Mode changes while a request is running
- **WHEN** the background state machine changes mode after a request has acquired its route
- **THEN** that request SHALL retain its original route or fail and SHALL NOT continue on the new store

### Requirement: Home isolates automatically with fresh local state
Home SHALL transition from `CONNECTED` to `ISOLATED` only after configurable failure hysteresis confirms Redis unavailability. The transition SHALL occur between requests and SHALL create a fresh process-local authorization and session store with a new isolation epoch; it MUST NOT seed that store from connected Redis data or a stale cache.

#### Scenario: Home loses Internet and private transit
- **WHEN** Home crosses the configured Redis failure threshold
- **THEN** new LAN password-login flows SHALL use the fresh local isolation epoch without operator action

#### Scenario: Home restarts while disconnected
- **WHEN** the Home authorization-server process restarts before Redis connectivity returns
- **THEN** it SHALL create a new isolation epoch and prior isolated sessions and grants SHALL require reauthentication

### Requirement: Cloud fails closed without Redis
Cloud SHALL NOT create or use a local authorization-state fallback. When Redis control or data state is unavailable, Cloud SHALL reject stateful authorization operations while continuing to expose safe stateless metadata and health behavior where possible.

#### Scenario: Redis is unavailable to Cloud
- **WHEN** a request would create, consume, or mutate Cloud authorization state while Redis is unavailable
- **THEN** Cloud SHALL return temporary failure without issuing state through an in-memory fallback

### Requirement: Home recovers automatically through generation fencing
After an isolation event, Home SHALL require a configurable stable-success window, acquire an owner-bound Redis lease and expiring recovery gate, drain stateful requests, and atomically advance the active generation before returning to `CONNECTED`. Home-local state SHALL be discarded and MUST NOT be merged into Redis.

#### Scenario: Stable connectivity returns
- **WHEN** Redis remains healthy for the configured recovery window and Home obtains the recovery lease
- **THEN** the system SHALL gate new stateful requests, advance the generation once, discard isolated Home state, and resume connected operation without administrative action

#### Scenario: Home crashes before generation advance
- **WHEN** the recovery owner stops after creating the gate but before committing the generation change
- **THEN** the lease and gate SHALL expire, Cloud SHALL resume the unchanged generation, and a later Home recovery attempt SHALL be safe

#### Scenario: Generation advance response is lost
- **WHEN** the atomic generation change commits but Home does not receive its response
- **THEN** Home SHALL determine the committed outcome from Redis control state and SHALL NOT advance the generation a second time for the same recovery

### Requirement: Obsolete state cannot cross generations or owners
Authorization and session identifiers SHALL identify their Redis generation or Home isolation epoch. Lookup and mutation SHALL reject state belonging to an obsolete generation, a different isolation epoch, or the other store owner. Physical deletion of old Redis generations MAY occur asynchronously only after logical invalidation.

#### Scenario: Browser presents a pre-recovery session
- **WHEN** a browser sends a session identifier from an older Redis generation after recovery
- **THEN** the authorization server SHALL treat it as absent and require a new login

#### Scenario: Isolated authorization code reaches connected mode
- **WHEN** a code created in a Home isolation epoch is presented after Home reconnects
- **THEN** the connected service SHALL reject it and SHALL NOT search local isolated state

### Requirement: Recovery does not claim JWT revocation
Generation changes SHALL invalidate stored grants and sessions but SHALL NOT be treated as revocation of already issued self-contained JWTs. Access tokens SHALL use a short configured lifetime, refresh-token grant issuance SHALL remain disabled, and recovery SHALL NOT rotate signing keys.

#### Scenario: JWT was issued before recovery
- **WHEN** a resource server receives an otherwise valid unexpired JWT issued before the generation changed
- **THEN** it SHALL continue normal issuer, signature, audience, scope, role, and expiry validation until that JWT expires
