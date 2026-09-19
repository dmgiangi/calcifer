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
- Make the broker available internally and through an authenticated TLS
  endpoint on `mqtt.calcifer.tech:8883` for LAN clients.
- Expose the coordinator using the exact stable host path selected by the
  operator, while constraining Zigbee2MQTT to `calcifer-home`.
- Enable MQTT Home Assistant discovery and provide a repeatable validation and
  rollback procedure.

**Non-Goals:**

- Reflashing or upgrading the coordinator firmware.
- Running ZHA and Zigbee2MQTT concurrently against the same coordinator.
- Deploying an MQTT operator, a multi-node broker cluster, or Internet-facing
  MQTT access.
- Fully automating Home Assistant's MQTT config-flow onboarding.

## Decisions

### Mosquitto without an operator

Use the Eclipse Mosquitto image in a single-replica StatefulSet (or equivalent
single-replica workload), with a PVC for broker data and a ConfigMap/Secret for
configuration and credentials. Mosquitto is intentionally chosen over RabbitMQ
because retained-message and wildcard-subscription behavior is a core
Home Assistant discovery requirement. An operator would add lifecycle
complexity without providing value for this single-broker deployment.

### Separate internal and TLS Services

Expose port 1883 through a ClusterIP Service for in-cluster Zigbee2MQTT and
Home Assistant traffic. Expose port 8883 through a separate LAN-reachable
Service, backed by the same Mosquitto pod, so the unencrypted listener is not
accidentally published externally. The TLS Service uses `mqtt.calcifer.tech`, a
cert-manager Certificate, and the existing Home LAN DNS target
`192.168.0.102`.

### TLS and credentials

Use the existing production Azure DNS ClusterIssuer to issue the broker
certificate. Keep broker credentials in a SOPS-encrypted Secret and mount the
certificate Secret read-only. TLS server authentication is required for the
8883 listener; username/password authentication remains required on both
listeners.

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

### Application boundaries

Keep Mosquitto and Zigbee2MQTT resources in dedicated application directories
and namespaces, with Kustomize resources included from the Home applications
root. Do not modify the existing Home Assistant deployment unless validation
shows a required compatibility change. Home Assistant will be connected to
Mosquitto through its supported MQTT config flow using the documented broker
endpoint and credentials.

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
  where practical, and document recovery and coordinator backup steps.
- **[Risk]** Certificate issuance or LAN DNS propagation can delay TLS startup.
  **Mitigation:** deploy Certificate and DNSEndpoint declaratively, validate
  cert-manager readiness before testing MQTT, and retain an internal listener
  for in-cluster recovery.
- **[Risk]** MQTT credentials must be shared by broker, Zigbee2MQTT, and the
  manual Home Assistant setup. **Mitigation:** keep them SOPS-encrypted and
  document retrieval without committing plaintext values.

## Migration Plan

1. Apply namespaces, PVCs, SOPS Secrets, Mosquitto configuration, Certificate,
   DNSEndpoint, and Services through Flux.
2. Wait for certificate issuance and verify the broker's internal health and
   authenticated 1883 listener.
3. Deploy Zigbee2MQTT and confirm the pod has the expected device path and
   coordinator connection.
4. Configure Home Assistant's MQTT integration and verify discovery, state,
   availability, and command round trips.
5. Test restart/recovery scenarios, including retained discovery after broker,
   Zigbee2MQTT, and Home Assistant restarts.
6. Roll back by suspending/removing the new application Kustomization and
   restoring from PVCs; do not delete the coordinator data PVC during a normal
   rollback.

## Open Questions

- Confirm the exact stable image versions available at implementation time.
- Confirm whether the cluster's LAN DNS controller watches the new namespace
  automatically or needs an explicit namespace/configuration update.
- Confirm the final SOPS secret keys and the operator-provided MQTT credentials
  before creating encrypted secret material.
