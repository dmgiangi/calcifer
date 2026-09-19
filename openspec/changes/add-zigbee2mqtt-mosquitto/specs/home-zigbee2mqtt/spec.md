## ADDED Requirements

### Requirement: Home runs a single persistent Zigbee2MQTT workload
The system SHALL run one Zigbee2MQTT replica on the node labeled
`kubernetes.io/hostname: calcifer-home`, with persistent storage for
configuration, coordinator state, device state, and logs.

#### Scenario: Zigbee2MQTT restarts with existing state
- **WHEN** the Zigbee2MQTT pod is recreated
- **THEN** it SHALL reuse its PVC and retain paired devices, network settings,
  and MQTT configuration

#### Scenario: Zigbee2MQTT is not scheduled away from the coordinator
- **WHEN** Kubernetes schedules or reschedules the workload
- **THEN** the pod SHALL run only on `calcifer-home`

### Requirement: Zigbee2MQTT directly accesses the SONOFF coordinator
The system SHALL mount the exact stable host path
`/dev/serial/by-id/usb-SONOFF_SONOFF_Dongle_Plus_CC2674P10_26319a63aafef01185fcea1f364a576b-if00-port0`
as a character device, expose it in the container as `/dev/ttyUSB0`, configure
the Texas Instruments Z-Stack adapter to use that path, and provide Unix group
GID 20 to the pod.

#### Scenario: Coordinator is available through the stable mount
- **WHEN** the Zigbee2MQTT pod starts on `calcifer-home`
- **THEN** `/dev/ttyUSB0` SHALL be present, readable/writable by the workload,
  and backed by the SONOFF host device rather than a regular file

#### Scenario: Coordinator ownership is exclusive
- **WHEN** Zigbee2MQTT is running
- **THEN** no second Zigbee coordinator workload SHALL be configured to access
  the same host device

### Requirement: Zigbee2MQTT connects to Mosquitto and publishes discovery
The system SHALL configure Zigbee2MQTT with authenticated MQTT connectivity to
the internal Mosquitto Service, the `zigbee2mqtt` base topic, and Home Assistant
MQTT discovery enabled.

#### Scenario: Zigbee device state reaches MQTT
- **WHEN** a paired Zigbee device reports a state change
- **THEN** Zigbee2MQTT SHALL publish the device state and availability to the
  configured Mosquitto broker

#### Scenario: Home Assistant discovery is published
- **WHEN** Zigbee2MQTT starts with Home Assistant discovery enabled
- **THEN** it SHALL publish retained discovery topics that Home Assistant can
  consume through its MQTT integration

### Requirement: Zigbee2MQTT configuration is version-pinned and secret-safe
The system SHALL use an explicitly pinned stable Zigbee2MQTT image version and
SHALL keep MQTT credentials and other sensitive values in SOPS-encrypted
resources or runtime Secret references.

#### Scenario: Deployment does not silently upgrade images
- **WHEN** Flux reconciles the Zigbee2MQTT workload
- **THEN** it SHALL use the image tag recorded in Git and SHALL not use the
  mutable `latest` tag

#### Scenario: Secret values are absent from rendered Git configuration
- **WHEN** a reviewer reads the repository manifests
- **THEN** MQTT passwords and other sensitive values SHALL be encrypted or
  referenced through Kubernetes Secrets rather than stored in plaintext
