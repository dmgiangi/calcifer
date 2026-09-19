## ADDED Requirements

### Requirement: Home provides a persistent authenticated Mosquitto broker
The system SHALL run exactly one Mosquitto broker replica on `calcifer-home`
with persistent storage for broker state, configuration managed in Git, and
username/password authentication enabled for every MQTT listener.

#### Scenario: Broker state survives a pod restart
- **WHEN** the Mosquitto pod is recreated on `calcifer-home`
- **THEN** broker configuration, retained messages, and persistent session data
  SHALL remain available from the attached PVC

#### Scenario: Unauthenticated MQTT access is rejected
- **WHEN** a client connects to either broker listener without valid
  credentials
- **THEN** Mosquitto SHALL reject the connection and SHALL not allow publish or
  subscribe operations

### Requirement: Home exposes a LAN TLS MQTT endpoint
The system SHALL expose Mosquitto TLS on `mqtt.calcifer.tech:8883` through a
LAN-reachable Service, using a cert-manager-issued certificate from the Home
production ClusterIssuer and retaining port 1883 as an in-cluster-only
ClusterIP listener.

#### Scenario: LAN client establishes a verified TLS session
- **WHEN** an authorized LAN client connects to `mqtt.calcifer.tech:8883`
  using the issued hostname
- **THEN** the broker SHALL present a currently valid certificate matching that
  hostname and SHALL accept the authenticated MQTT session

#### Scenario: Unencrypted listener is not externally published
- **WHEN** a client outside the cluster inspects the broker's published
  Services
- **THEN** port 1883 SHALL not be exposed by the LAN-facing Service

### Requirement: Home publishes a declarative broker DNS record
The system SHALL declare `mqtt.calcifer.tech` through the existing Home LAN DNS
and ExternalDNS-compatible `DNSEndpoint` mechanism with target
`192.168.0.102`.

#### Scenario: LAN DNS resolves the MQTT hostname
- **WHEN** a LAN client queries `mqtt.calcifer.tech`
- **THEN** the Home resolver SHALL return `192.168.0.102`

### Requirement: Broker secrets remain encrypted in Git
The system SHALL store MQTT credentials and any broker secret configuration in
SOPS-encrypted resources and SHALL not commit plaintext passwords, private keys,
or certificate material.

#### Scenario: Flux reconciles broker secrets
- **WHEN** Flux reconciles the MQTT application Kustomization
- **THEN** it SHALL decrypt and apply the required Secret using the existing
  repository age key without exposing plaintext secret values in Git

### Requirement: Broker supports Home Assistant discovery traffic
The system SHALL preserve retained MQTT messages and wildcard subscriptions
required by Zigbee2MQTT and Home Assistant MQTT discovery.

#### Scenario: Discovery remains available after Home Assistant restart
- **WHEN** Home Assistant reconnects after a restart while Zigbee2MQTT remains
  connected
- **THEN** retained discovery and availability messages SHALL allow previously
  discovered entities to reappear without re-pairing devices
