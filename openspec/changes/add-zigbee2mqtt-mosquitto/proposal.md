## Why

The Home cluster has a SONOFF Dongle Plus CC2674P10 attached to `calcifer-home`,
but no GitOps-managed MQTT broker or Zigbee workload. Adding Mosquitto and
Zigbee2MQTT now provides a reliable, Home Assistant-compatible path for SONOFF
and other Zigbee devices while keeping the coordinator tied to the node that
physically hosts it.

## What Changes

- Deploy a single-replica, persistent Mosquitto broker in `calcifer-home`.
- Provide an internal MQTT listener and a LAN-reachable TLS listener at
  `mqtt.calcifer.tech:8883` using a cert-manager-issued certificate.
- Store MQTT authentication material and other sensitive configuration through
  the repository's existing SOPS/age workflow.
- Deploy a single-replica Zigbee2MQTT workload on `calcifer-home`, using the
  stable SONOFF device path under `/dev/serial/by-id` as the direct host device
  mount and the host `dialout` group (GID 20).
- Persist Mosquitto retained/session state and Zigbee2MQTT configuration,
  coordinator state, and backups on `local-path` PVCs.
- Configure Zigbee2MQTT for the Texas Instruments coordinator and MQTT Home
  Assistant discovery, and document the Home Assistant MQTT integration and
  validation procedure.
- Add the LAN DNS record and canonical certificate required for
  `mqtt.calcifer.tech`.

## Capabilities

### New Capabilities

- `home-mqtt-broker`: GitOps-managed Mosquitto availability, authentication,
  persistence, LAN TLS access, and retained-message behavior.
- `home-zigbee2mqtt`: GitOps-managed Zigbee2MQTT deployment, direct coordinator
  device access, persistence, MQTT connectivity, and Home Assistant discovery.

### Modified Capabilities

None.

## Impact

- Adds manifests under `clusters/calcifer-home/apps` and corresponding Flux
  application reconciliation resources.
- Adds cert-manager, ExternalDNS/LAN DNS, Service, PVC, Secret, Deployment or
  StatefulSet, and Kustomize resources for the new workloads.
- Requires the existing `local-path` storage class, cert-manager production
  ClusterIssuer, SOPS age key, and schedulable node `calcifer-home`.
- Adds no new external dependency or operator; container image versions will be
  pinned to the latest stable releases selected during implementation.
- Home Assistant MQTT integration onboarding remains a config-flow operation;
  the change supplies the broker endpoint, credentials, and discovery settings
  needed for that integration.
