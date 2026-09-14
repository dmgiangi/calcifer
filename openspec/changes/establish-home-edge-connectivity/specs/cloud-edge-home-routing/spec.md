## ADDED Requirements

### Requirement: Cloud remains the public edge for Home-hosted services
The system SHALL keep selected public `*.calcifer.tech` service names resolving
to the Cloud VPS. Cloud Traefik SHALL route a selected Home-hosted hostname to
its Home backend through private transit while preserving the canonical Host
and HTTPS forwarded metadata.

#### Scenario: Internet client reaches a Home-hosted service
- **WHEN** an Internet client requests HTTPS for a selected Home-hosted
  `calcifer.tech` hostname
- **THEN** Cloud Traefik SHALL terminate the public TLS connection and proxy
  the request through private transit to the matching Home ingress route

#### Scenario: Service placement changes without changing its public name
- **WHEN** a selected service is moved from a Cloud backend to a Home backend
- **THEN** clients SHALL continue to use the same canonical hostname without a
  required OAuth redirect URI, bookmark, or DNS-name migration

### Requirement: Cloud validates the Home backend TLS connection
The system SHALL proxy Cloud-to-Home traffic over HTTPS and SHALL validate the
Home certificate using the canonical service name as TLS server name.

#### Scenario: Home backend presents an invalid certificate
- **WHEN** the Home backend certificate is expired, untrusted, or does not
  match the canonical service hostname
- **THEN** Cloud Traefik SHALL fail the backend connection rather than forward
  traffic over an unvalidated TLS connection

### Requirement: Direct LAN access is explicit and equivalently protected
The system SHALL permit direct LAN access to a selected Home service only when
the Home ingress route enforces authorization controls equivalent to those
required on its Cloud edge route. The direct route SHALL use the same canonical
hostname and valid TLS certificate.

#### Scenario: A service relies on Cloud-only access control
- **WHEN** a selected service depends on an authorization middleware available
  only at the Cloud edge
- **THEN** its LAN DNS override SHALL remain disabled until an equivalent Home
  control is configured

#### Scenario: A protected Home service supports direct LAN access
- **WHEN** an authorized LAN client uses an enabled canonical hostname that
  resolves to the Home node
- **THEN** Home Traefik SHALL enforce the required control and serve the
  service with valid HTTPS without routing the request through Cloud
