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

The authorization-state Redis path is deliberately narrower than general
cluster transit: Home may connect to Cloud `172.31.255.1:16379` only through
`wg0`. Cloud resolves the internal Service DNS name
`authorization-state-redis.authorization.svc.cluster.local` at WireGuard
startup and DNATs only source `172.31.255.2` on port `16379` to Service port
`6379`. No Pod or Service CIDR route is added.

Home Assistant backup egress is also deliberately narrow: Home may use the
HTTP CONNECT proxy at Cloud `172.31.255.1:3128` only through `wg0`. Envoy binds
only to the Cloud tunnel address, accepts only source `172.31.255.2`, and
permits only `stcalciferbackupitn.blob.core.windows.net:443`. It does not terminate TLS
or expose a general-purpose forward proxy.

Azure Speech egress uses a separate HTTP CONNECT proxy at Cloud
`172.31.255.1:3129`. It also binds only to the tunnel address and accepts only
source `172.31.255.2`, but permits only
`speech-calcifer-home-itn.cognitiveservices.azure.com:443`. The Home Wyoming
adapters explicitly configure this proxy through the Speech SDK; generic
`HTTP_PROXY`/`HTTPS_PROXY` variables are insufficient for this SDK runtime.
Azure Speech network ACLs deny by default and allow only the Cloud public IP.
Home SNATs only pod traffic destined for `172.31.255.1:3129` to its WireGuard
address `172.31.255.2`; no default route or arbitrary Pod CIDR is tunneled.

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
- `clusters/apps/authorization-server/overlays/home/authorization-server-secrets.sops.yaml`
- `clusters/apps/homepage/overlays/home-web/homepage-secrets.sops.yaml`
- `clusters/apps/homepage/overlays/cloud-web/homepage-secrets.sops.yaml`
- `clusters/calcifer-cloud/apps/authorization-state/redis-auth.sops.yaml`
- `clusters/apps/authorization-server/overlays/home/redis-auth.sops.yaml`

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
for the Home node. Preserve that invariant: public traffic
must enter through the Cloud VPS and private transit only.

During a controlled outage test, record the router configuration, make the
Home DNS Service unavailable, and confirm that the router reports DNS failure;
general Internet resolution is not expected to continue under this accepted
constraint. Restore the Service and confirm the declared Home name returns
`192.168.0.102`.

Useful checks from a LAN client are:

```sh
dig @192.168.0.102 auth.calcifer.tech A
dig @192.168.0.102 calcifer.tech A
dig @192.168.0.102 example.com A
```

The `auth.calcifer.tech` answer is reserved for the Home authorization-server
route and points to `192.168.0.102`. Keep the identity hostname on the same
certificate and issuer as the public Cloud route. The Home authorization-server
Flux Kustomization can serve the Google-capable path with the common signing
key, client credentials, and Google registration. Add the password hash to both
encrypted Secrets before enabling the local password fallback.

Before enabling resilient state, verify that the SOPS-encrypted
`authorization/redis-auth` manifests reconcile with the expected
`AUTH_STATE_REDIS_USERNAME` and `AUTH_STATE_REDIS_PASSWORD` keys. Never decrypt
or print those credentials during operational checks.

The retired `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` names are
not certificate or ingress aliases. Do not add them back as OAuth endpoint
profiles or DNS overrides.

Homepage uses the same split-horizon pattern: public `calcifer.tech` resolves
to Cloud, while the Home `DNSEndpoint` resolves it to `192.168.0.102`. See
`docs/homepage-operations.md` for catalog, OIDC client, and rollout procedures.

## Azure DNS and certificates

Create a dedicated Azure application/service principal restricted to the
`calcifer.tech` DNS zone and the record-set operations required for DNS-01.
Store its client ID, tenant ID, subscription/resource-group identifiers, and
client secret only in the encrypted Home cert-manager Secret. Reconcile the
staging issuer first, then production. Home certificates must use canonical
hostnames for selected Home services; Home need not be Internet reachable for
DNS-01.

## Verification

Check Flux and the resources without exposing Secret data:

```sh
flux --context calcifer-home get kustomizations
flux --context calcifer-cloud get kustomizations
kubectl --context calcifer-home -n private-transit get pods
kubectl --context calcifer-cloud -n private-transit get pods
```

On the nodes, verify a recent WireGuard handshake and only the intended peer
route. Test HTTPS with the canonical hostname and confirm Home receives the
original Host header. Test a non-HTTPS tunnel port and arbitrary Pod/Service
CIDR addresses to confirm they are rejected or unrouted.

Run the Redis reachability checks as status-only checks; redirect output and do
not provide a password or issue a Redis command that prints stored values:

```sh
nc -z -w 2 172.31.255.1 16379 >/dev/null 2>&1; printf 'redis-private-reachability exit=%s\n' "$?"
nc -z -w 2 172.31.255.1 16380 >/dev/null 2>&1; printf 'undeclared-port exit=%s\n' "$?"
nc -z -w 2 172.31.255.1 3129 >/dev/null 2>&1; printf 'speech-proxy-reachability exit=%s\n' "$?"
```

The Redis and Speech proxy checks are expected to succeed only from the Home
tunnel path. The undeclared port and any public-path attempt are expected to
fail. These checks validate reachability only and never expose credentials or
stored values. For Speech, also verify that the named endpoint fails without
the proxy and that a Wyoming TTS-to-STT round trip succeeds through the proxy.

Confirm that `auth.calcifer.tech` and `grafana.calcifer.tech` remain unchanged.
When
connected, authorization telemetry should report `CONNECTED`; after the
 configured three failed two-second probes Home may report `ISOLATED` for at
 least 15 seconds. A 30-second stable-success window, a 30-second lease, a
 20-second gate, and a three-second drain precede automatic generation recovery.

## Rollback and incident recovery

For a Home edge route rollback, remove or suspend the Cloud edge route first,
then the Home route and backend. Keep certificates and transit intact if they
are healthy. For a transit incident, suspend the private-transit Flux
Kustomizations, remove the affected peer/key, and rotate both peer keys after
containment. Restore router DNS to its prior upstream configuration if the
Home resolver is unhealthy.
