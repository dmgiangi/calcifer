# Calcifer

This repository contains firmware and the configuration for two K3s clusters:

- **calcifer-cloud** — hosted on a remote VPS and used for cloud-facing workloads.
- **calcifer-home** — hosted on a server inside the local home network and used for services that should remain on-premises.

The configurations for both clusters are kept in the same repository so they can be versioned and maintained consistently. When adding or changing manifests, make sure to apply them to the intended cluster only.

All Kubernetes manifests live under [`clusters/`](clusters/). Shared application
bases and their cluster overlays live in `clusters/apps/`; each cluster's Flux
configuration selects the applicable overlay.

## Therment firmware

[`therment/`](therment/README.md) contains the ESP32 fermentation controller
firmware, build and flashing instructions, and local configuration templates.
It connects over the Home LAN to the Mosquitto broker on `calcifer-home`; see
the [MQTT operations guide](docs/zigbee2mqtt-operations.md#therment-lan-mqtt)
for the broker, topic contract, and safe credential provisioning. Firmware is
built and flashed separately from the Flux-managed cluster configuration.

## GitOps

Flux watches the `master` branch and reconciles the cluster configurations from
[`clusters/calcifer-cloud`](clusters/calcifer-cloud/) and
[`clusters/calcifer-home`](clusters/calcifer-home/). See the
[Cloud cluster documentation](clusters/calcifer-cloud/README.md) for details.

## Clusters

| Cluster | Location | Purpose |
| --- | --- | --- |
| `calcifer-cloud` | Remote VPS | Public or cloud workloads |
| `calcifer-home` | Local network | Home and on-premises workloads |

## Notes

K3s administration commands must be run with access to the corresponding cluster. Keep credentials and other secrets outside the repository.
