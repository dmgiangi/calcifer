## 1. Confirm versions and repository integration points

- [x] 1.1 Inspect the Home applications Kustomization and Flux reconciliation order, then choose namespaces and resource directories for Mosquitto and Zigbee2MQTT.
- [x] 1.2 Select the newest stable Mosquitto and Zigbee2MQTT image releases available at implementation time, pin their tags, and record the selections in the manifests.
- [x] 1.3 Confirm the existing `local-path` StorageClass, production Home ClusterIssuer, LAN DNS watch configuration, and SOPS/age workflow required by the new resources.

## 2. Implement the persistent Mosquitto broker

- [x] 2.1 Create the Mosquitto namespace, PVC, Kustomization, and pinned single-replica workload with readiness/liveness checks and persistent broker data.
- [x] 2.2 Add broker configuration with authenticated internal port 1883, including retained-message persistence and Home Assistant-compatible wildcard subscriptions.
- [x] 2.3 Create SOPS-encrypted MQTT credentials and wire the broker password file without committing plaintext values.
- [x] 2.4 Add an authenticated ClusterIP Service for port 1883 and keep MQTT unexposed outside the cluster.
- [x] 2.5 Confirm that no broker certificate is required for the in-cluster-only transport and remove the unused cert-manager resource.
- [x] 2.6 Remove the unused external MQTT DNS record and include the remaining Mosquitto resources in the application Kustomization and Flux root.

## 3. Implement Zigbee2MQTT and coordinator access

- [x] 3.1 Create the Zigbee2MQTT namespace, PVC, Kustomization, and pinned single-replica workload constrained to `calcifer-home`.
- [x] 3.2 Mount the exact SONOFF `/dev/serial/by-id` path as a `hostPath` character device at `/dev/ttyUSB0`, add supplemental group GID 20, and configure the TI Z-Stack adapter.
- [x] 3.3 Configure Zigbee2MQTT to authenticate to the internal Mosquitto Service, use the `zigbee2mqtt` base topic, enable Home Assistant discovery, and keep secrets out of plaintext configuration.
- [x] 3.4 Include Zigbee2MQTT resources in the application Kustomization and Flux root without changing the existing Home Assistant workload unnecessarily.
- [x] 3.5 Register a `zigbee2mqtt` Authorization Code client with PKCE and place the identical generated client secret in both authorization-server overlays and the Zigbee2MQTT SOPS Secret.
- [x] 3.6 Add the pinned OAuth2 Proxy sidecar, proxy-only Service, Traefik HTTPS route, public Certificate, LAN split-horizon DNSEndpoint, and ingress restriction without exposing frontend port 8080.

## 4. Validate deployment and Home Assistant integration

- [x] 4.1 Render and validate all Kustomize manifests, including hostPath, Service, Secret, PVC, and scheduling fields.
- [x] 4.2 Reconcile the Home Flux resources and verify internal DNS resolution, broker authentication, and connectivity on `mosquitto.mqtt.svc.cluster.local:1883`.
- [x] 4.3 Verify Zigbee2MQTT starts on `calcifer-home`, opens the mounted coordinator, connects to Mosquitto, and publishes retained discovery and availability topics.
- [ ] 4.4 Configure Home Assistant's MQTT integration through its supported config flow and verify discovery, state updates, commands, and availability.
- [x] 4.5 Test restart and recovery scenarios for Mosquitto, Zigbee2MQTT, and Home Assistant, including retained discovery after broker restart and coordinator state preservation.
- [x] 4.6 Document normal rollback and recovery, including preserving both PVCs and avoiding simultaneous access to the coordinator.
- [x] 4.7 Render and validate both authorization-server overlays and the Zigbee2MQTT manifests, including secret references, PKCE metadata, probes, and proxy-only routing.
- [ ] 4.8 Reconcile the affected Flux resources and verify certificate readiness, LAN DNS, unauthenticated OIDC redirect, authenticated frontend access, and continued internal-only MQTT operation.
