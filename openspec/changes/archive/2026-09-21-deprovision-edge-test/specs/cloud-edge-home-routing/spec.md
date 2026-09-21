# Spec Delta

## ADDED Requirements

### Requirement: Decommissioned edge-test route is absent

The system SHALL NOT publish `edge-test.calcifer.tech` through the Cloud or
Home edge path after the deprovisioning change. It SHALL remove the managed
Home LAN DNS record, edge routes, backend service, and certificates associated
with that hostname, without changing routing for other selected Home services.

#### Scenario: Flux reconciles the deprovisioned edge state

- **WHEN** the Cloud and Home application Kustomizations reconcile the desired
  state after `edge-test` removal
- **THEN** neither cluster SHALL retain an `edge-test` namespace or an
  `edge-test` backend, Service, ingress route, DNS endpoint, or certificate

#### Scenario: Former edge-test hostname is no longer served by the platform

- **WHEN** a client requests `edge-test.calcifer.tech` after DNS and ingress
  reconciliation complete
- **THEN** the request SHALL NOT reach the former edge-test backend through
  Cloud or direct Home LAN routing

#### Scenario: Other Home edge services remain available

- **WHEN** a client requests another selected Home service after the
  deprovisioning reconciliation
- **THEN** its existing Cloud and explicitly enabled Home routing SHALL remain
  unchanged
