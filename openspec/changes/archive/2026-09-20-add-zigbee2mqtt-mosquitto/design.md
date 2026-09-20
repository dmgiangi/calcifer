## Context

`calcifer-home` is a single-node K3s cluster running on Ubuntu. The SONOFF
Dongle Plus CC2674P10 is attached to that node and is exposed by the host as a
stable `/dev/serial/by-id` symlink resolving to `/dev/ttyUSB0`. The device is
owned by `root:dialout` with GID 20 and is not currently used by another
process.

The repository already manages Home Assistant, cert-manager, SOPS secrets,
LAN DNS, and persistent workloads through Flux and Kustomize. No MQTT broker
or Zigbee workload is currently deployed.

## Goals / Non-Goals

**Goals:**

- Deploy a small, recoverable MQTT and Zigbee stack using repository-managed
  Kubernetes resources.
- Preserve MQTT retained messages and Zigbee2MQTT state across pod restarts.
- Back up Zigbee2MQTT data to a private Azure Blob container independently of
  the local runtime PVC.
- Make the broker available internally through an authenticated ClusterIP
  Service for Home Assistant and Zigbee2MQTT.
- Expose the coordinator using the exact stable host path selected by the
  operator, while constraining Zigbee2MQTT to `calcifer-home`.
- Enable MQTT Home Assistant discovery and provide a repeatable validation and
  rollback procedure.
- Publish the Zigbee2MQTT browser frontend to LAN and Internet clients through
  a canonical HTTPS hostname protected by the existing Calcifer identity plane.

**Non-Goals:**

- Reflashing or upgrading the coordinator firmware.
- Running ZHA and Zigbee2MQTT concurrently against the same coordinator.
- Deploying an MQTT operator, a multi-node broker cluster, or Internet-facing
  MQTT access.
- Fully automating Home Assistant's MQTT config-flow onboarding.
- Publishing the MQTT listener or the raw Zigbee2MQTT frontend directly outside
  the cluster.

## Decisions

### Mosquitto without an operator

Use the Eclipse Mosquitto image in a single-replica StatefulSet (or equivalent
single-replica workload), with a PVC for broker data and a ConfigMap/Secret for
configuration and credentials. Mosquitto is intentionally chosen over RabbitMQ
because retained-message and wildcard-subscription behavior is a core
Home Assistant discovery requirement. An operator would add lifecycle
complexity without providing value for this single-broker deployment.

### Internal ClusterIP Service

Expose only port 1883 through a ClusterIP Service for in-cluster Zigbee2MQTT
and Home Assistant traffic. Do not publish an external MQTT Service because
all current consumers run inside the cluster.

### Credentials and transport

Keep broker credentials in a SOPS-encrypted Secret. Username/password
authentication remains required on the internal listener. Transport encryption
is intentionally omitted because the broker is reachable only through the
cluster network; TLS can be added later with an internal CA or split DNS if an
external client is required.

### Direct stable device mount

Mount the exact host path
`/dev/serial/by-id/usb-SONOFF_SONOFF_Dongle_Plus_CC2674P10_26319a63aafef01185fcea1f364a576b-if00-port0`
as a `hostPath` character device. Present it in the container at
`/dev/ttyUSB0`, configure Zigbee2MQTT with that container path, and add
`supplementalGroups: [20]`. Use a node selector for
`kubernetes.io/hostname: calcifer-home` and one replica to prevent concurrent
coordinator access.

### Pinned stable images

Select the newest stable Mosquitto and Zigbee2MQTT releases available when the
implementation starts, then pin immutable image tags in Git. Do not use the
`latest` tag. Record the selected versions in the manifests and validation
notes so upgrades are deliberate.

### Zigbee2MQTT backup repository

Run a dedicated scheduled CronJob in `calcifer-home` that snapshots the
Zigbee2MQTT data PVC and uploads the snapshot with the repository's existing
Restic pattern to a private Azure Blob container dedicated to Zigbee2MQTT
backups. Use the existing restricted Cloud CONNECT proxy for Azure HTTPS
egress, retain at least seven daily snapshots, and keep Azure credentials and
the Restic repository password in a SOPS-encrypted Secret. The backup job must
not expose a new Service or inbound endpoint and must not replace the PVC as
the primary runtime store.

### Application boundaries

Keep Mosquitto and Zigbee2MQTT resources in dedicated application directories
and namespaces, with Kustomize resources included from the Home applications
root. Do not modify the existing Home Assistant deployment unless validation
shows a required compatibility change. Home Assistant will be connected to
Mosquitto through its supported MQTT config flow using the documented broker
endpoint and credentials.

### Authenticated Zigbee2MQTT web edge

Expose the frontend as `https://zigbee.calcifer.tech` through dual Traefik
edges. Publish the public Azure DNS A record to Cloud `136.144.222.128`. Cloud
Traefik terminates a publicly trusted certificate, then uses a selectorless
Service and EndpointSlice to forward HTTPS over WireGuard to Home
`172.31.255.2:443`. A ServersTransport SHALL send
`zigbee.calcifer.tech` as the upstream TLS server name and preserve the Host
header. Home Traefik terminates its own publicly trusted certificate and routes
only to OAuth2 Proxy.

Publish a Home LAN split-horizon A record to `192.168.0.102` so local clients
reach Home Traefik directly. Both certificates use the existing
`letsencrypt-production-azure` ClusterIssuer. Neither path publishes MQTT or
creates an MQTT LoadBalancer, and no residential router port-forward is used.

Run OAuth2 Proxy `v7.15.4` as a non-privileged sidecar in the Zigbee2MQTT pod.
Traefik and the ClusterIP Service target only the proxy on port 4180; the proxy
is the sole HTTP caller of the Zigbee2MQTT frontend on loopback port 8080. Use
the canonical `https://auth.calcifer.tech` issuer, Authorization Code with PKCE
S256, an exact callback URI, secure host-only cookies, and the `roles` claim to
allow only the `admin` role. Store the OIDC client and cookie secrets in SOPS
resources and register identical client credentials in both authorization
server instances because they share one canonical issuer.

## Risks / Trade-offs

- **[Risk]** A `hostPath` mount of a symlink may be rejected by a runtime or
  resolve differently than expected. **Mitigation:** validate the rendered
  resource and pod startup on K3s; fail deployment rather than silently using
  `/dev/ttyUSB0` as the host source, and document any required Kubernetes
  adjustment. The K3s/containerd device cgroup also requires the Zigbee2MQTT
  container to be privileged when a raw character device is exposed with
  `hostPath`; keep that privilege limited to Zigbee2MQTT and do not grant it to
  Mosquitto or unrelated workloads.
- **[Risk]** A single coordinator and single broker are availability points of
  failure. **Mitigation:** use PVCs, Recreate/single-replica semantics, backups
  to the private Azure repository, and document recovery and coordinator
  backup steps.
- **[Risk]** An in-cluster-only broker cannot serve clients outside Kubernetes.
  **Mitigation:** add a separately reviewed TLS listener and access path if a
  LAN or external MQTT client becomes necessary.
- **[Risk]** MQTT credentials must be shared by broker, Zigbee2MQTT, and the
  manual Home Assistant setup. **Mitigation:** keep them SOPS-encrypted and
  document retrieval without committing plaintext values.
- **[Risk]** Exposing the administration frontend without an authentication
  boundary would allow Zigbee network changes. **Mitigation:** route the Service
  exclusively to OAuth2 Proxy, require OIDC and the `admin` role, restrict proxy
  ingress to Traefik, and never expose port 8080 through a Service.
- **[Risk]** The Cloud edge could bypass Home authentication or fail upstream
  TLS verification. **Mitigation:** forward only to Home Traefik on
  `172.31.255.2:443`, preserve the canonical Host header, validate the Home
  certificate with canonical SNI, and keep OAuth2 Proxy as the sole Home
  application backend.
- **[Risk]** Authorization server or Internet loss can interrupt a new browser
  login. **Mitigation:** existing secure proxy sessions remain valid for their
  configured lifetime, Home split DNS routes the canonical issuer locally, and
  Zigbee/MQTT processing remains independent of browser access.

## Migration Plan

1. Apply namespaces, PVCs, SOPS Secrets, Mosquitto configuration, and the
   internal Service through Flux.
2. Verify the broker's internal health and authenticated 1883 listener.
3. Deploy Zigbee2MQTT and confirm the pod has the expected device path and
   coordinator connection.
4. Configure Home Assistant's MQTT integration and verify discovery, state,
   availability, and command round trips.
5. Test restart/recovery scenarios, including retained discovery after broker,
   Zigbee2MQTT, and Home Assistant restarts.
6. Add and validate the scheduled Zigbee2MQTT backup job, Azure repository,
   retention policy, protected credentials, and restore procedure.
7. Register the OIDC client, deploy the OAuth2 Proxy sidecar and Home HTTPS
   edge, and verify unauthenticated redirects plus authenticated frontend access.
8. Deploy the Cloud HTTPS edge over WireGuard and verify the public certificate,
   canonical upstream TLS, OAuth2 redirect, and LAN split-horizon path.
9. Roll back by suspending/removing the new application Kustomization and
   restoring from PVCs; do not delete the coordinator data PVC during a normal
   rollback.

## Open Questions

- Confirm the exact stable image versions available at implementation time.
- Confirm the final SOPS secret keys and the operator-provided MQTT credentials
  before creating encrypted secret material.
