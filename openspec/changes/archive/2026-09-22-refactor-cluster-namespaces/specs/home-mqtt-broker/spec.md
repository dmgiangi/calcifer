# Spec Delta

## MODIFIED Requirements

### Requirement: Home provides a persistent authenticated Mosquitto broker
The system SHALL run exactly one Mosquitto broker replica on `calcifer-home` in the `home-automation` namespace with persistent storage for broker state, configuration managed in Git, and username/password authentication enabled for every MQTT listener. As an explicit exception during the planned namespace migration, the replacement broker MAY start with an empty data PVC and discard previously queued messages, retained messages, and persistent sessions; encrypted credentials and declarative configuration SHALL be preserved, and normal persistence guarantees SHALL resume after cutover.

#### Scenario: Broker state survives a pod restart
- **WHEN** the post-migration Mosquitto pod is recreated on `calcifer-home`
- **THEN** broker configuration, retained messages, and persistent session data created after cutover SHALL remain available from the attached PVC

#### Scenario: Unauthenticated MQTT access is rejected
- **WHEN** a client connects to the broker listener without valid credentials
- **THEN** Mosquitto SHALL reject the connection and SHALL not allow publish or subscribe operations

#### Scenario: Planned namespace migration recreates broker state
- **WHEN** Mosquitto is cut over from `mqtt` to `home-automation`
- **THEN** the replacement broker MAY use a new empty PVC
- **AND** it SHALL regenerate its authenticated users from the preserved SOPS-encrypted credentials before accepting clients

### Requirement: Broker supports Home Assistant discovery traffic
The system SHALL preserve retained MQTT messages and wildcard subscriptions required by Zigbee2MQTT and Home Assistant MQTT discovery during normal post-migration operation. Following the one-time broker-state reset allowed for namespace migration, Zigbee2MQTT SHALL republish discovery and availability state from its restored device database so Home Assistant can reconstruct MQTT discovery without re-pairing Zigbee devices.

#### Scenario: Discovery remains available after Home Assistant restart
- **WHEN** Home Assistant reconnects after a normal restart while Zigbee2MQTT remains connected
- **THEN** retained discovery and availability messages SHALL allow previously discovered entities to reappear without re-pairing devices

#### Scenario: Discovery recovers after namespace migration
- **WHEN** the replacement Mosquitto broker starts with empty state and restored Zigbee2MQTT connects
- **THEN** Zigbee2MQTT SHALL republish the required retained discovery and availability topics
- **AND** Home Assistant SHALL rediscover the devices without requiring Zigbee re-pairing
