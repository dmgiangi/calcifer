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
- **WHEN** a client connects to the broker listener without valid credentials
- **THEN** Mosquitto SHALL reject the connection and SHALL not allow publish or
  subscribe operations

### Requirement: Home exposes only an authenticated internal MQTT endpoint
The system SHALL expose Mosquitto only through an authenticated ClusterIP
Service on port 1883 for in-cluster consumers.

#### Scenario: MQTT is not externally published
- **WHEN** a client outside the cluster inspects the broker's published Services
- **THEN** no external MQTT Service or TLS endpoint SHALL be created

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
