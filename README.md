# Calcifer Kubernetes Clusters

This repository contains the configuration for two K3s clusters:

- **calcifer-cloud** — hosted on a remote VPS and used for cloud-facing workloads.
- **calcifer-home** — hosted on a server inside the local home network and used for services that should remain on-premises.

The configurations for both clusters are kept in the same repository so they can be versioned and maintained consistently. When adding or changing manifests, make sure to apply them to the intended cluster only.

## GitOps

`calcifer-cloud` is bootstrapped with Flux. Flux watches the `master` branch and reconciles the cluster from [`clusters/calcifer-cloud`](clusters/calcifer-cloud/). See the [cluster documentation](clusters/calcifer-cloud/README.md) for details.

## Clusters

| Cluster | Location | Purpose |
| --- | --- | --- |
| `calcifer-cloud` | Remote VPS | Public or cloud workloads |
| `calcifer-home` | Local network | Home and on-premises workloads |

## Notes

K3s administration commands must be run with access to the corresponding cluster. Keep credentials and other secrets outside the repository.
