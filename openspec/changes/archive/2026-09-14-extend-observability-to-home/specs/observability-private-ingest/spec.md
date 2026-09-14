## ADDED Requirements

### Requirement: Private Thanos ingest endpoint
The system SHALL provide an HTTPS endpoint on `calcifer-cloud` at the private
WireGuard-only Traefik NodePort `32443` that accepts
Prometheus remote-write requests from the authenticated Home Alloy and routes
them to Thanos Receive.

#### Scenario: Authenticated Home metrics forwarding
- **WHEN** Alloy Home sends a valid remote-write request over WireGuard with
  the configured TLS and BasicAuth credentials
- **THEN** the endpoint SHALL forward the request to Thanos Receive and return
  the backend result

#### Scenario: Unauthenticated metrics forwarding
- **WHEN** a request lacks valid network source, TLS, or BasicAuth credentials
- **THEN** the endpoint SHALL reject the request before it reaches Thanos
  Receive

### Requirement: Private Loki ingest endpoint
The system SHALL provide an HTTPS endpoint on `calcifer-cloud` at the private
WireGuard-only Traefik NodePort `32443` that accepts
Loki push requests from the authenticated Home Alloy and routes them to Loki.

#### Scenario: Authenticated Home log forwarding
- **WHEN** Alloy Home sends a valid Loki push request over WireGuard with the
  configured TLS and Loki-specific BasicAuth credentials
- **THEN** the endpoint SHALL forward the request to Loki and return the
  backend result

#### Scenario: Unauthenticated log forwarding
- **WHEN** a request lacks valid network source, TLS, or Loki credentials
- **THEN** the endpoint SHALL reject the request before it reaches Loki

### Requirement: Ingest endpoints are not public query endpoints
The system SHALL keep the private ingest routes distinct from backend query and
administrative routes and SHALL not publish Thanos or Loki APIs for Internet
access.

#### Scenario: Internet client probes an ingest route
- **WHEN** an Internet client attempts to reach a private ingest hostname or
  route without the WireGuard source and credentials
- **THEN** the request SHALL be rejected and SHALL not expose backend data

#### Scenario: Backend query path remains private
- **WHEN** a client attempts to use the private ingest route as a Thanos or
  Loki query endpoint
- **THEN** the route SHALL not proxy query or administrative APIs

### Requirement: Explicit node-level transit
The system SHALL extend Cloud↔Home transit only with the explicit node-level
routes and source translation required for the two ingest flows and SHALL not
route arbitrary Pod or Service CIDRs between clusters.

#### Scenario: Home Alloy reaches private ingest
- **WHEN** a Home Alloy request targets the configured Cloud WireGuard endpoint
- **THEN** the request SHALL traverse the authenticated tunnel and reach only
  the declared HTTPS ingest route

#### Scenario: Arbitrary cross-cluster service access
- **WHEN** a workload attempts to access an undeclared address in the other
  cluster's Pod or Service network
- **THEN** the transit configuration SHALL not provide a route for that access
