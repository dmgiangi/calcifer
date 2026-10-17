# Home LAN DNS

## Purpose

Define how the Home LAN resolves Home service names, and its operational
constraints.

## Requirements

### Requirement: The Home router resolves Home service names
The Home LAN router SHALL hold static DNS records that resolve the selected
Home service hostnames in the `calcifer.tech` zone to the static Home address
`192.168.0.102`. The cluster SHALL NOT run a LAN DNS resolver; K3s cluster DNS
serves only in-cluster workloads.

#### Scenario: LAN client resolves a Home service name
- **WHEN** a LAN client queries the router for a Home service hostname that has
  a static record
- **THEN** the router SHALL return `192.168.0.102`

### Requirement: The router preserves public resolution for other names
The router SHALL forward queries that do not match a static record to public
upstream resolvers and SHALL NOT use DNS servers learned dynamically from the
ISP (peer DNS disabled). A Cloud-hosted canonical name without a static record
SHALL therefore retain its public DNS answer.

#### Scenario: LAN client queries a Cloud-hosted name
- **WHEN** a LAN client queries the router for a Cloud-hosted `calcifer.tech`
  hostname that has no static record
- **THEN** the router SHALL return the public upstream answer

### Requirement: Home service records are maintained on the router
Each Home service hostname that must resolve to the Home address SHALL have a
matching static record on the router. The record set is not managed by Flux and
SHALL be restored from the documented router configuration when the router is
replaced or reset.

#### Scenario: A new Home service is published
- **WHEN** a new Home service hostname is added to the repository
- **THEN** the operator SHALL add the matching static router record before LAN
  clients are expected to reach it
