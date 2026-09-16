## MODIFIED Requirements

### Requirement: Cloud and Home have authenticated encrypted edge transit
The system SHALL establish a WireGuard tunnel between the Cloud VPS and Home node using private tunnel addresses from a range that does not overlap either cluster's Pod, Service, or Home LAN networks. Home SHALL maintain the tunnel without an inbound Internet port-forward on the home router. Transit SHALL permit only explicitly declared node-level flows, including Cloud-to-Home private ingress and Home-to-Cloud authorization-state Redis access.

#### Scenario: Cloud reaches the permitted Home ingress through the tunnel
- **WHEN** the Cloud edge sends traffic to a selected Home service backend
- **THEN** the traffic SHALL traverse the authenticated encrypted tunnel to the configured Home tunnel address and permitted ingress port

#### Scenario: Home reaches the dedicated Cloud Redis endpoint
- **WHEN** the Home authorization server connects to the declared Cloud tunnel address and private Redis port
- **THEN** the traffic SHALL reach only the dedicated Redis service through WireGuard and SHALL still require Redis ACL authentication

#### Scenario: Home targets another Cloud node port
- **WHEN** a Home workload sends tunnel traffic to a Cloud port not explicitly declared for private transit
- **THEN** the Cloud firewall SHALL drop that traffic

#### Scenario: An Internet host targets a private transit port
- **WHEN** a host outside the WireGuard interface attempts to reach the Home private ingress or Cloud Redis private port
- **THEN** it SHALL not reach the selected service through that port