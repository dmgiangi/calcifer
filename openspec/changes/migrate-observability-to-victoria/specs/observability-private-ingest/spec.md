## RENAMED Requirements

- FROM: `### Requirement: Private Thanos ingest endpoint`
- TO: `### Requirement: Private metrics ingest endpoint`
- FROM: `### Requirement: Private Loki ingest endpoint`
- TO: `### Requirement: Private logs ingest endpoint`

## ADDED Requirements

### Requirement: Private traces ingest endpoint
The system SHALL provide an HTTPS endpoint on `calcifer-cloud` at the private WireGuard-only Traefik NodePort `32443` (`traces-ingest.calcifer.tech`) that accepts OpenTelemetry trace requests from the authenticated Home Alloy and routes them to VictoriaTraces.

#### Scenario: Authenticated Home trace forwarding
- **WHEN** Alloy Home sends a valid OpenTelemetry trace push request over WireGuard with the configured TLS and BasicAuth credentials
- **THEN** the endpoint SHALL forward the request to VictoriaTraces and return the backend result.

#### Scenario: Unauthenticated trace forwarding
- **WHEN** a request lacks valid network source, TLS, or BasicAuth credentials
- **THEN** the endpoint SHALL reject the request before it reaches VictoriaTraces.

## MODIFIED Requirements

### Requirement: Private metrics ingest endpoint
The system SHALL provide an HTTPS endpoint on `calcifer-cloud` at the private WireGuard-only Traefik NodePort `32443` (`metrics-ingest.calcifer.tech`) that accepts Prometheus remote-write requests from the authenticated Home Alloy and routes them to VictoriaMetrics.

#### Scenario: Authenticated Home metrics forwarding
- **WHEN** Alloy Home sends a valid remote-write request over WireGuard with the configured TLS and BasicAuth credentials
- **THEN** the endpoint SHALL forward the request to VictoriaMetrics and return the backend result.

#### Scenario: Unauthenticated metrics forwarding
- **WHEN** a request lacks valid network source, TLS, or BasicAuth credentials
- **THEN** the endpoint SHALL reject the request before it reaches VictoriaMetrics.

### Requirement: Private logs ingest endpoint
The system SHALL provide an HTTPS endpoint on `calcifer-cloud` at the private WireGuard-only Traefik NodePort `32443` (`logs-ingest.calcifer.tech`) that accepts Loki push requests from the authenticated Home Alloy and routes them to VictoriaLogs.

#### Scenario: Authenticated Home log forwarding
- **WHEN** Alloy Home sends a valid Loki push request over WireGuard with the configured TLS and BasicAuth credentials
- **THEN** the endpoint SHALL forward the request to VictoriaLogs and return the backend result.

#### Scenario: Unauthenticated log forwarding
- **WHEN** a request lacks valid network source, TLS, or BasicAuth credentials
- **THEN** the endpoint SHALL reject the request before it reaches VictoriaLogs.

### Requirement: Ingest endpoints are not public query endpoints
The system SHALL keep the private ingest routes distinct from backend query and administrative routes and SHALL not publish VictoriaMetrics, VictoriaLogs, or VictoriaTraces APIs for Internet access.

#### Scenario: Internet client probes an ingest route
- **WHEN** an Internet client attempts to reach a private ingest hostname or route without the WireGuard source and credentials
- **THEN** the request SHALL be rejected and SHALL not expose backend data.

#### Scenario: Backend query path remains private
- **WHEN** a client attempts to use the private ingest route as a query endpoint
- **THEN** the route SHALL not proxy query or administrative APIs.
