## Why

`calcifer-home` is now Flux-managed but cannot yet host services that are
reachable safely from either the local network or the Internet. Establishing a
private Cloud-to-Home transport and making the Cloud cluster the public edge
lets workloads move to Home later without changing their public URLs or
exposing the home network directly.

## What Changes

- Install cert-manager and Let's Encrypt DNS-01 ClusterIssuers on
  `calcifer-home`, using SOPS-encrypted Azure DNS credentials and the existing
  repository SOPS age key material.
- Establish an authenticated, encrypted private tunnel between the Cloud VPS
  and the static Home node address. Home initiates or maintains the tunnel
  without requiring an inbound Internet port-forward on the home router.
- Configure Cloud Traefik as the sole public edge for `*.calcifer.tech` and
  allow selected hostnames to proxy to Home through the private tunnel while
  preserving the original Host and HTTPS forwarding metadata.
- Issue valid certificates for Home-hosted service names so they work both
  through the Cloud edge and directly from the local network.
- Run a GitOps-managed LAN DNS resolver on Home. It resolves declarative
  Kubernetes `DNSEndpoint` records for selected Home-hosted canonical names and
  forwards all other requests to a public upstream resolver.
- Restrict Home ingress so tunneled traffic is accepted only from the private
  tunnel and local-network traffic follows an explicit policy.
- Retain the existing `auth.calcifer.tech` and `grafana.calcifer.tech`
  hostname convention; this change does not migrate observability workloads.

## Capabilities

### New Capabilities

- `home-certificate-issuance`: Flux-managed cert-manager and DNS-01
  certificate issuance for Home workloads.
- `cloud-home-private-transit`: Encrypted, authenticated node-to-node
  connectivity for Cloud-to-Home service traffic.
- `cloud-edge-home-routing`: Public Cloud ingress routes selected canonical
  hostnames to Home services without directly exposing Home to the Internet.
- `home-lan-dns`: A LAN DNS service that resolves selected Home service names
  from Kubernetes resources while preserving public DNS resolution for all
  other names.

### Modified Capabilities

None.

## Impact

- Adds Home-cluster infrastructure manifests and Flux Kustomizations under
  `clusters/calcifer-home`.
- Adds encrypted cluster Secret provisioning steps outside Git for the Home
  SOPS age key; DNS and WireGuard private material is SOPS-encrypted before it
  is committed.
- Adds privileged, host-networked WireGuard DaemonSets, networking, Traefik,
  LAN DNS, and Azure DNS certificate-validation configuration.
- Introduces WireGuard as the initial private-transport implementation; it
  avoids the overlapping K3s Pod CIDRs that make a basic Submariner deployment
  unsuitable for this narrowly scoped edge path.
