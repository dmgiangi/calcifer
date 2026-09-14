# Cluster identity routing

## Purpose

Define canonical issuer compatibility, stateful endpoint routing, split-horizon
DNS, and TLS/ingress behavior for Cloud and Home identity access.

## Requirements

### Requirement: Canonical issuer and stateful endpoint profiles are separate
The system SHALL retain `https://auth.calcifer.tech` as the sole token issuer.
It SHALL expose `auth-cloud.calcifer.tech` as a public Cloud stateful OAuth
endpoint and `auth-home.calcifer.tech` as a Home-LAN stateful OAuth endpoint.
Cloud application deployments SHALL use the Cloud endpoint for authorization,
token, and user-info requests; Home application deployments SHALL use the Home
endpoint. Neither endpoint SHALL create a second issuer.

#### Scenario: LAN browser signs in to a Cloud application
- **WHEN** a LAN browser starts a Cloud application's OAuth flow
- **THEN** its authorization request and the Cloud backend's token exchange
  SHALL both reach the Cloud authorization-server through
  `auth-cloud.calcifer.tech`

#### Scenario: LAN browser signs in to a Home application
- **WHEN** a LAN browser starts a Home application's OAuth flow
- **THEN** its authorization request and the Home backend's token exchange
  SHALL both reach the Home authorization-server through
  `auth-home.calcifer.tech` without requiring Internet access

### Requirement: Compatibility hostname remains split-horizon
The system SHALL ensure that public DNS resolves `auth.calcifer.tech` to the
Cloud edge, while Home
split-horizon DNS SHALL resolve it to the Home edge. This hostname SHALL remain
valid for issuer and JWKS validation compatibility, but applications whose
browser and backend can have different network locality SHALL NOT use it as a
stateful OAuth endpoint profile.

#### Scenario: Issuer validation uses the compatibility hostname
- **WHEN** an application validates a token issued through either endpoint
- **THEN** it SHALL accept the canonical issuer `https://auth.calcifer.tech`
  without depending on the endpoint hostname that issued the token

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
Normal Cloud authentication SHALL be served by the Cloud authorization-server
instance, and normal Home authentication SHALL be served by the Home instance.
Neither path SHALL require the Cloud/Home WireGuard tunnel to be available.

#### Scenario: Home tunnel is down
- **WHEN** the Cloud/Home private transit tunnel is unavailable
- **THEN** Cloud public authentication and Home LAN authentication SHALL remain
  independently reachable through their local edges

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
