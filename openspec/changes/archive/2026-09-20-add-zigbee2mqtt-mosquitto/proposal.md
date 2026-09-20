## Why

The Home cluster has a SONOFF Dongle Plus CC2674P10 attached to `calcifer-home`,
but no GitOps-managed MQTT broker or Zigbee workload. Adding Mosquitto and
Zigbee2MQTT now provides a reliable, Home Assistant-compatible path for SONOFF
and other Zigbee devices while keeping the coordinator tied to the node that
physically hosts it.

## What Changes

- Deploy a single-replica, persistent Mosquitto broker in `calcifer-home`.
- Provide an authenticated internal MQTT listener for Home Assistant and
  Zigbee2MQTT through a ClusterIP Service.
- Store MQTT authentication material and other sensitive configuration through
  the repository's existing SOPS/age workflow.
- Deploy a single-replica Zigbee2MQTT workload on `calcifer-home`, using the
  stable SONOFF device path under `/dev/serial/by-id` as the direct host device
  mount and the host `dialout` group (GID 20).
- Persist Mosquitto retained/session state and Zigbee2MQTT configuration,
  coordinator state, and runtime data on `local-path` PVCs, with scheduled
  encrypted Zigbee2MQTT backups copied to a dedicated private Azure Blob
  Storage container.
- Configure Zigbee2MQTT for the Texas Instruments coordinator and MQTT Home
  Assistant discovery, and document the Home Assistant MQTT integration and
  validation procedure.
- Expose only the Zigbee2MQTT web frontend at `https://zigbee.calcifer.tech`
  from both the LAN and Internet. LAN clients route directly to Home through
  split-horizon DNS, while public clients enter through Cloud Traefik and the
  existing WireGuard private transit. Both paths terminate publicly trusted
  TLS and reach an OAuth2 Proxy sidecar authenticated by the Calcifer
  authorization server.
- Keep MQTT unexposed outside the cluster; a future external endpoint would
  require a separately reviewed TLS and DNS design.

## Capabilities

### New Capabilities

- `home-mqtt-broker`: GitOps-managed Mosquitto availability, authentication,
  internal persistence, and retained-message behavior.
- `home-zigbee2mqtt`: GitOps-managed Zigbee2MQTT deployment, direct coordinator
  device access, persistence, MQTT connectivity, and Home Assistant discovery.

### Modified Capabilities

None.

## Impact

- Adds workload and LAN-edge manifests under `clusters/calcifer-home/apps`,
  public-edge forwarding manifests under `clusters/calcifer-cloud/apps`, and
  corresponding Flux application reconciliation resources.
- Adds Service, PVC, Secret, Deployment, Traefik IngressRoute, Certificate,
  DNSEndpoint, EndpointSlice, ServersTransport, NetworkPolicy, and Kustomize
  resources for the new workloads and dual edge path.
- Requires the existing `local-path` storage class, SOPS age key, production
  ClusterIssuer, LAN DNS controller, authorization server, and schedulable node
  `calcifer-home`, plus the existing restricted Cloud Azure egress path for
  backup uploads.
- Adds the pinned OAuth2 Proxy sidecar image but no new operator.
- Home Assistant MQTT integration onboarding remains a config-flow operation;
  the change supplies the broker endpoint, credentials, and discovery settings
  needed for that integration.
