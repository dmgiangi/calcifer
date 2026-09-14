## ADDED Requirements

### Requirement: The canonical identity hostname has local and public paths
The system SHALL expose `auth.calcifer.tech` through Cloud for public Internet
clients and through Home for LAN clients. Public DNS SHALL resolve the name to
the Cloud edge, while Home split-horizon DNS SHALL resolve it to the Home
edge. Both paths SHALL preserve the same hostname and HTTPS issuer.

#### Scenario: Internet client reaches identity service
- **WHEN** an Internet client resolves `auth.calcifer.tech`
- **THEN** the request SHALL reach the Cloud Traefik route and the Cloud
  authorization-server instance

#### Scenario: LAN client reaches identity service
- **WHEN** a LAN client resolves `auth.calcifer.tech` through the Home resolver
- **THEN** the request SHALL reach the Home Traefik route and the Home
  authorization-server instance without requiring Internet access

### Requirement: Home identity access is independent of the Internet
The Home DNS, TLS, ingress, authorization-server Service, and password login path SHALL
continue to operate when Home cannot reach external DNS, Google, or
the Internet. Certificate renewal is an operational prerequisite and SHALL
not be required during a temporary outage if the current certificate remains
valid.

#### Scenario: Home loses upstream Internet
- **WHEN** a LAN client accesses a Home application while Home's Internet path
  is unavailable
- **THEN** it SHALL resolve and reach the canonical identity endpoint locally
  and complete password authentication

### Requirement: Identity routing does not depend on the private transit tunnel
Normal Cloud authentication SHALL be served by the Cloud authorization-server
instance, and normal Home authentication SHALL be served by the Home instance.
Neither path SHALL require the Cloud/Home WireGuard tunnel to be available.

#### Scenario: Home tunnel is down
- **WHEN** the Cloud/Home private transit tunnel is unavailable
- **THEN** Cloud public authentication and Home LAN authentication SHALL remain
  independently reachable through their local edges

### Requirement: Both paths enforce equivalent TLS and ingress protection
Cloud and Home SHALL serve a valid certificate for `auth.calcifer.tech`, route
only the intended identity endpoints to the authorization-server Service, and
apply equivalent security headers, request limits, and network restrictions.

#### Scenario: Client uses an invalid hostname or direct Service address
- **WHEN** a caller bypasses the canonical HTTPS route or targets the Service
  directly
- **THEN** the request SHALL not become an alternative unauthenticated public
  identity path
