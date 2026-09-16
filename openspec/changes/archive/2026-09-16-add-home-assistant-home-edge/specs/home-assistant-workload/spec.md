## ADDED Requirements

### Requirement: Dedicated Home Assistant deployment on Home cluster
The system SHALL run Home Assistant Core as a single-replica workload on `calcifer-home` using persistent storage for `/config` backed by the `local-path` storage class and a `Recreate` update strategy to prevent concurrent SQLite database access.

#### Scenario: Home Assistant container starts with persistent storage
- **WHEN** the Home Assistant pod starts or restarts on `calcifer-home`
- **THEN** it SHALL mount the dedicated persistent volume at `/config` preserving existing state, entity registries, and SQLite database files.

#### Scenario: Home Assistant trusts reverse proxy headers
- **WHEN** Home Assistant receives an HTTP or WebSocket request from Traefik with `X-Forwarded-For` and `X-Forwarded-Proto`
- **THEN** it SHALL accept the forwarded headers and resolve the actual client IP address without triggering reverse proxy security errors.

### Requirement: Split-horizon access and edge transit routing
The system SHALL expose Home Assistant at the canonical hostname `home.calcifer.tech` via both direct LAN access and remote Cloud edge routing.

#### Scenario: LAN client accesses Home Assistant directly
- **WHEN** a client on the home local network resolves `home.calcifer.tech` via LAN DNS
- **THEN** the DNS query SHALL return the Home ingress IP `192.168.0.102`, and Home Traefik SHALL serve the request over valid TLS without passing traffic through Cloud.

#### Scenario: Internet client accesses Home Assistant via Cloud edge
- **WHEN** an Internet client connects to `https://home.calcifer.tech`
- **THEN** the request SHALL terminate TLS on Cloud Traefik, proxy over the WireGuard private transit (`172.31.255.2:443`) to Home Traefik with TLS SNI validation, and reach Home Assistant.

#### Scenario: Real-time WebSocket connection establishes
- **WHEN** a web browser or mobile client opens a WebSocket connection to `https://home.calcifer.tech/api/websocket`
- **THEN** Traefik on both Cloud and Home edges SHALL upgrade the connection and stream real-time events without disconnects or timeouts.

#### Scenario: Home Assistant published to Homepage dashboard
- **WHEN** Homepage renders the service catalog
- **THEN** it SHALL display Home Assistant with canonical URL `https://home.calcifer.tech` and health monitoring.
