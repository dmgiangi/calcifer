## ADDED Requirements

### Requirement: Cloud and Home have authenticated encrypted edge transit
The system SHALL establish a WireGuard tunnel between the Cloud VPS and Home
node using private tunnel addresses from a range that does not overlap either
cluster's Pod, Service, or Home LAN networks. Home SHALL be able to maintain
the tunnel without an inbound Internet port-forward on the home router.

#### Scenario: Cloud reaches the permitted Home ingress through the tunnel
- **WHEN** the Cloud edge sends traffic to a selected Home service backend
- **THEN** the traffic SHALL traverse the authenticated encrypted tunnel to the
  configured Home tunnel address and permitted ingress port

#### Scenario: An Internet host targets the Home node directly
- **WHEN** a host on the public Internet attempts to establish a connection to
  a selected Home service
- **THEN** it SHALL not reach the Home node through a public router forwarding
  rule or a public DNS destination

### Requirement: Private transit does not join overlapping Kubernetes overlays
The system SHALL limit the private transport to explicitly declared node-level
edge routes and SHALL NOT route either cluster's Pod or Service CIDRs across
the tunnel.

#### Scenario: A Cloud Pod targets an arbitrary Home Pod address
- **WHEN** a Cloud workload attempts to reach an arbitrary address in the Home
  Kubernetes Pod or Service network
- **THEN** the tunnel configuration SHALL not provide a route for that traffic
