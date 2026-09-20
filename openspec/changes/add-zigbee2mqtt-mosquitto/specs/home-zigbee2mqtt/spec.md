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

### Requirement: Zigbee2MQTT provides an authenticated HTTPS frontend
The system SHALL expose the Zigbee2MQTT frontend at
`https://zigbee.calcifer.tech` to both LAN and Internet clients using publicly
trusted certificates and an OAuth2 Proxy sidecar authenticated by
`https://auth.calcifer.tech`. LAN clients SHALL resolve the canonical hostname
to Home `192.168.0.102`. Internet clients SHALL resolve it to Cloud
`136.144.222.128`, where Cloud Traefik SHALL forward HTTPS over WireGuard to
Home `172.31.255.2:443` with canonical SNI and the original Host header.

#### Scenario: Public browser access uses the Cloud private-transit edge
- **WHEN** an Internet client resolves and requests `zigbee.calcifer.tech`
- **THEN** it SHALL connect to Cloud Traefik with a publicly trusted certificate
  and the request SHALL traverse WireGuard to Home Traefik without an inbound
  residential router port-forward

#### Scenario: LAN browser access uses the direct Home edge
- **WHEN** a LAN client resolves and requests `zigbee.calcifer.tech`
- **THEN** split-horizon DNS SHALL return `192.168.0.102` and the client SHALL
  connect directly to Home Traefik with a publicly trusted certificate

#### Scenario: Unauthenticated browser access requires login
- **WHEN** a browser without a valid proxy session requests the frontend
- **THEN** it SHALL be redirected into the Calcifer authorization flow and SHALL
  not reach Zigbee2MQTT directly

#### Scenario: An administrator reaches the frontend
- **WHEN** a user completes OIDC Authorization Code with PKCE and has the
  `admin` role
- **THEN** OAuth2 Proxy SHALL establish a secure host-only session and proxy the
  request, including WebSocket traffic, to the loopback frontend

#### Scenario: The raw frontend remains private
- **WHEN** cluster Services and Traefik routes are inspected
- **THEN** only OAuth2 Proxy port 4180 SHALL be routed and Zigbee2MQTT port 8080
  SHALL NOT be directly exposed; the Cloud edge SHALL forward only to Home
  Traefik HTTPS on `172.31.255.2:443`

#### Scenario: Browser secrets remain encrypted
- **WHEN** a reviewer inspects the OIDC client and proxy configuration in Git
- **THEN** client and cookie secrets SHALL exist only in SOPS-encrypted Secret
  resources while non-secret client metadata remains declarative
