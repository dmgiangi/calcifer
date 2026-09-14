## ADDED Requirements

### Requirement: Home provides a declarative LAN DNS resolver
The system SHALL run a dedicated CoreDNS resolver with the `k8s-gateway`
plugin on `calcifer-home`, separate from K3s cluster DNS. It SHALL be exposed
at the static Home address on both UDP and TCP port 53, and it SHALL watch only
the `DNSEndpoint` resources required to resolve selected Home service names in
the `calcifer.tech` zone.

#### Scenario: Router resolves a declared Home service name
- **WHEN** the LAN router forwards a DNS query for a declared Home service
  hostname to `192.168.0.102`
- **THEN** the resolver SHALL return the target recorded in its corresponding
  Kubernetes `DNSEndpoint`

### Requirement: LAN DNS preserves public resolution for undeclared names
The system SHALL forward undeclared queries directly to `1.1.1.1` and SHALL
not forward them back to the LAN router. A Cloud-hosted canonical name without
a Home `DNSEndpoint` SHALL therefore retain its public DNS answer.

#### Scenario: Router queries a Cloud-hosted name
- **WHEN** the LAN router queries the Home resolver for an undeclared
  Cloud-hosted `calcifer.tech` hostname
- **THEN** the resolver SHALL return the public upstream answer without a DNS
  forwarding loop

### Requirement: Router fallback does not bypass local routing during health
The LAN router SHALL use the Home resolver as its primary upstream and
Cloudflare as fallback. The system SHALL verify that fallback preserves general
Internet resolution during a Home resolver outage and that normal operation
uses declared Home records.

#### Scenario: Home resolver becomes unavailable
- **WHEN** the Home LAN DNS resolver is unavailable
- **THEN** the router SHALL retain general Internet DNS resolution through its
  Cloudflare fallback and a declared Home record SHALL not be reported as a
  successful local resolution
