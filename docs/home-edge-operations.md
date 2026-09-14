# Home edge operations guide

This guide covers the manual prerequisites and operational checks for the
`calcifer-home` edge path. It contains no credentials or private key material.
All Kubernetes resources described below are reconciled from this repository;
manual changes are for host, router, DNS-provider, and bootstrap state only.

## Required access and manual prerequisites

| Area | Required privilege | Required state |
| --- | --- | --- |
| Cloud VPS | root or equivalent host-network administrator | Public address `136.144.222.128`, UDP `51820` permitted, and K3s node access |
| Home server | root or equivalent host-network administrator | Static address `192.168.0.102`, `/dev/net/tun`, WireGuard kernel/userspace support, and K3s node access |
| Kubernetes | cluster-admin during bootstrap; read-only access for checks | Flux bootstrapped for both clusters |
| SOPS | access to the repository age private key outside Git | `flux-system/sops-age` Secret exists in both clusters |
| Router | LAN/DHCP and DNS administrator | Home resolver is primary upstream; fallback is unavailable on the deployed router; no inbound port-forward to Home |
| Azure | DNS-zone administrator | Dedicated least-privilege client can edit `_acme-challenge` in the `calcifer.tech` zone |
| Public DNS | DNS-zone administrator | Selected public names point to the Cloud VPS; Home-only overrides are not public records |

## Host and cluster bootstrap

Install K3s on each single-node host using the site’s approved hardening and
backup procedure. Confirm kubeconfig access and context names before applying
Flux. Do not place kubeconfigs, join tokens, age keys, WireGuard private keys,
Azure credentials, or router credentials in this repository.

Before enabling the private-transit Kustomization on either cluster, verify:

```sh
test -c /dev/net/tun
modprobe wireguard 2>/dev/null || true
wg --version
ip route
```

Confirm that `172.31.255.0/30` does not overlap host, LAN, Pod, or Service
routes. The tunnel addresses are Cloud `172.31.255.1` and Home `172.31.255.2`.
The manifests intentionally do not route `10.42.0.0/16`, Service CIDRs, or
arbitrary LAN networks through WireGuard.

## Flux, SOPS, and secret locations

Provision the existing repository age private key out-of-band as the Secret
`flux-system/sops-age` in each cluster. Verify only its metadata, never print
its value:

```sh
kubectl --context calcifer-home -n flux-system get secret sops-age
kubectl --context calcifer-cloud -n flux-system get secret sops-age
```

Encrypted secret locations are:

- `clusters/calcifer-home/infrastructure/private-transit/wireguard-private-key.sops.yaml`
- `clusters/calcifer-cloud/infrastructure/private-transit/wireguard-private-key.sops.yaml`
- `clusters/calcifer-home/infrastructure/cert-manager/config/azure-dns-credentials.sops.yaml`
- `clusters/calcifer-home/apps/edge-test/basic-auth.sops.yaml`
- `clusters/calcifer-cloud/apps/edge-test/basic-auth.sops.yaml`

Rotate WireGuard keys by generating a new pair on a trusted administrative
machine, replacing only the encrypted Secret values and the opposite public
key, then reconciling both clusters. Never place private material in shell
history, logs, manifests, or incident tickets.

## Router and DNS operations

Advertise only the router as the LAN DNS server through DHCP. Configure
`192.168.0.102` as the primary upstream. The deployed router cannot provide a
Cloudflare fallback for this upstream, so Home resolver availability is an
accepted operational prerequisite for LAN DNS. Do not configure the Home
resolver to forward to the router: it forwards undeclared queries directly to
`1.1.1.1`.

The router has been manually checked and has no inbound NAT/port-forward rule
for the Home node or the test service. Preserve that invariant: public traffic
must enter through the Cloud VPS and private transit only.

During a controlled outage test, record the router configuration, make the
Home DNS Service unavailable, and confirm that the router reports DNS failure;
general Internet resolution is not expected to continue under this accepted
constraint. Restore the Service and confirm the declared Home name returns
`192.168.0.102`.

Useful checks from a LAN client are:

```sh
dig @192.168.0.102 edge-test.calcifer.tech A
dig @192.168.0.102 auth.calcifer.tech A
dig @192.168.0.102 example.com A
```

## Azure DNS and certificates

Create a dedicated Azure application/service principal restricted to the
`calcifer.tech` DNS zone and the record-set operations required for DNS-01.
Store its client ID, tenant ID, subscription/resource-group identifiers, and
client secret only in the encrypted Home cert-manager Secret. Reconcile the
staging issuer first, then production. Home certificates must use canonical
hostnames such as `edge-test.calcifer.tech`; Home need not be Internet
reachable for DNS-01.

## Verification

Check Flux and the resources without exposing Secret data:

```sh
flux --context calcifer-home get kustomizations
flux --context calcifer-cloud get kustomizations
kubectl --context calcifer-home -n private-transit get pods
kubectl --context calcifer-cloud -n private-transit get pods
kubectl --context calcifer-home -n edge-test get certificate,ingressroute
kubectl --context calcifer-cloud -n edge-test get service,endpointslice,ingressroute
```

On the nodes, verify a recent WireGuard handshake and only the intended peer
route. Test HTTPS with the canonical hostname and confirm Home receives the
original Host header. Test a non-HTTPS tunnel port and arbitrary Pod/Service
CIDR addresses to confirm they are rejected or unrouted.

The public test path must resolve to Cloud. The LAN override may resolve it to
Home only while Home’s equivalent authorization policy is enabled. Confirm
that `auth.calcifer.tech` and `grafana.calcifer.tech` remain unchanged.

## Rollback and incident recovery

For a route rollback, remove or suspend the Cloud edge route first, then the
Home route and disposable backend. Keep certificates and transit intact if
they are healthy. For a transit incident, suspend the private-transit Flux
Kustomizations, remove the affected peer/key, and rotate both peer keys after
containment. Restore router DNS to its prior upstream configuration if the
Home resolver is unhealthy.

The protected `edge-test` route is retained as a non-sensitive operational
health endpoint. It requires the encrypted basic-auth Secret on both Cloud
and Home and is not a production application. Retain it only while that
authorization remains valid; otherwise remove the backend, both edge routes,
the LAN `DNSEndpoint`, and its certificates together.

Use its route pattern as the review baseline for later workload migrations:
Home canonical certificate and access control, optional LAN `DNSEndpoint`,
Cloud selectorless Service/EndpointSlice, canonical-SNI `ServersTransport`,
and a Cloud Traefik route preserving Host and HTTPS forwarding metadata.
