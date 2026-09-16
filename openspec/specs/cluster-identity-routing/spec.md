# Cluster identity routing

## Purpose

Define canonical issuer compatibility, stateful endpoint routing, split-horizon
DNS, and TLS/ingress behavior for Cloud and Home identity access.

## Requirements

### Requirement: Canonical hostname is the only stateful OAuth profile
The system SHALL ensure that public DNS resolves `auth.calcifer.tech` to the
Cloud edge, while Home split-horizon DNS SHALL resolve it to the Home edge.
`auth.calcifer.tech` SHALL remain the sole token issuer, stateful OAuth
hostname, and authorized Google callback. An anonymous request to its root
SHALL redirect to `/login`; an authenticated request SHALL reach a session page
with logout. The canonical authorization-server session SHALL be reused when a
later OAuth client starts its authorization request, but SHALL not be shared as
an application-domain cookie. The `auth-cloud.calcifer.tech` and
`auth-home.calcifer.tech` hostnames SHALL not be advertised or used as OAuth
endpoint profiles.

#### Scenario: Issuer and OAuth endpoints use the canonical hostname
- **WHEN** an application validates a token issued through either endpoint
- **THEN** it SHALL use and accept `https://auth.calcifer.tech` for issuer, authorization, token, user-info, and callback URLs

#### Scenario: LAN user starts Google login on the canonical hostname
- **WHEN** a LAN browser initiates Google login through `auth.calcifer.tech` and Internet access remains available
- **THEN** Google SHALL return to `https://auth.calcifer.tech/login/oauth2/code/google` and split-horizon DNS SHALL route the callback to Home

#### Scenario: User starts Google login through either network location
- **WHEN** a browser initiates Google login through the canonical hostname from Cloud or Home
- **THEN** Google SHALL return to `https://auth.calcifer.tech/login/oauth2/code/google`, with split-horizon DNS selecting the reachable local edge

#### Scenario: Direct canonical login enables client SSO
- **WHEN** an anonymous browser visits `https://auth.calcifer.tech/`
- **THEN** it SHALL be redirected to `/login`; after successful direct password or Google authentication it SHALL reach an authenticated session page, and a later `/oauth2/authorize` request for a registered client SHALL complete without another credential prompt while the same fenced authorization-server session remains valid

### Requirement: Home identity access is independent of the Internet
The Home identity access path SHALL continue to operate when Home cannot reach
external DNS, Google, or the Internet. This includes the Home DNS, TLS,
ingress, authorization-server Service, and password login path. Certificate
renewal is an operational prerequisite and SHALL not be required during a
temporary outage if the current certificate remains valid.

#### Scenario: Home loses upstream Internet
- **WHEN** a LAN client accesses a Home application while Home's Internet path
  is unavailable
- **THEN** it SHALL resolve and reach the Home stateful identity endpoint
  locally and complete password authentication

### Requirement: Identity routing does not depend on the private transit tunnel
Cloud and Home identity endpoints SHALL remain independently reachable through
their local ingress paths. Home MAY use the private transit tunnel for connected
Redis state, but loss of that dependency SHALL automatically move Home to
isolated local state after failure hysteresis; Cloud SHALL continue through
Redis when available. During generation recovery, stateful endpoints MAY return
temporary unavailability and SHALL resume without operator action.

#### Scenario: Home tunnel is down
- **WHEN** the Cloud/Home private transit tunnel becomes unavailable long enough to cross the failure threshold
- **THEN** Cloud public authentication SHALL remain available through Redis and Home LAN password authentication SHALL resume through fresh isolated state

#### Scenario: Home tunnel returns
- **WHEN** private transit and Redis remain healthy for the configured recovery window
- **THEN** automatic generation recovery SHALL complete and the canonical endpoint SHALL resume connected operation after affected users restart authentication

#### Scenario: Recovery is in progress
- **WHEN** the expiring recovery gate is active
- **THEN** stateful identity endpoints SHALL return temporary unavailability rather than writing authorization state during the generation transition

### Requirement: Both paths enforce equivalent TLS and ingress protection
Cloud and Home SHALL serve valid certificates for their endpoint profile and
the canonical compatibility hostname, route only intended identity hostnames
to the authorization-server Service, and apply equivalent security headers,
request limits, and network restrictions.

#### Scenario: Client uses an invalid hostname or direct Service address
- **WHEN** a caller bypasses an approved HTTPS route or targets the Service
  directly
- **THEN** the request SHALL not become an alternative unauthenticated public
  identity path
