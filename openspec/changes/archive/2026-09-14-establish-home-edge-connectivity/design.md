## Context

`calcifer-cloud` is a public single-node K3s VPS and already runs Traefik,
cert-manager, Flux, and Let's Encrypt DNS-01 issuers backed by Azure DNS.
`calcifer-home` is a single-node K3s cluster at the static LAN address
`192.168.0.102`; Flux is installed but it has no cert-manager, application
ingress, or public network path. Both clusters use the K3s default Pod CIDR
`10.42.0.0/24`, so treating their Pod networks as directly routable would
create address ambiguity.

The desired user experience is one canonical namespace, `*.calcifer.tech`.
Internet clients must enter through Cloud, including for services whose
workloads run at Home. LAN clients must be able to reach selected Home services
directly, using the same hostname. The repository SOPS age key is intentionally
shared between the clusters; its private material is provisioned outside Git.
The LAN router remains the DNS server advertised to clients and uses Home as
its primary upstream. The deployed router cannot provide a Cloudflare
fallback for that upstream, so Home resolver availability is an accepted LAN
operational prerequisite.

## Goals / Non-Goals

**Goals:**

- Make Cloud the public HTTPS edge for selected Home-hosted services without
  port-forwarding or otherwise directly exposing the home router.
- Provide mutually authenticated encrypted transport between the two nodes.
- Install Home certificate issuance that can produce valid certificates for
  canonical `*.calcifer.tech` service names using DNS-01.
- Keep Cloud and Home workload placement independent from the public hostname.
- Permit a direct LAN route to selected Home services with the same hostname,
  TLS validity, and explicit equivalent access controls.
- Provide a GitOps-managed LAN DNS resolver whose selected Home records are
  declarative Kubernetes resources and whose non-local queries retain reliable
  public resolution.

**Non-Goals:**

- Move Grafana, Loki, Thanos, or any other workload to Home.
- Build a general Pod-to-Pod or Kubernetes Service mesh between the clusters.
- Change existing public service names, OAuth redirect URIs, or issuers.
- Provide high availability for either single-node cluster or a generic remote
  user VPN.
- Expose the Home node, Kubernetes API, or arbitrary Home services to the
  Internet.

## Decisions

### Preserve canonical names and route by workload placement

Public Azure DNS records for selected `*.calcifer.tech` service names resolve
to the Cloud VPS. Cloud Traefik terminates the Internet-facing TLS connection
and dispatches by Host: services local to Cloud use ordinary Kubernetes
backends, while Home services use an explicitly declared tunnel-backed
backend. The proxy preserves the original Host and standard forwarded HTTPS
metadata.

This avoids a `*.cloud.calcifer.tech`/`*.home.calcifer.tech` migration and
therefore avoids changing existing URLs, OAuth registrations, browser
bookmarks, and service configuration. A separate namespace was rejected
because it exposes topology and makes workload migration a public breaking
change.

LAN DNS may override selected Home service names to `192.168.0.102`. The
override is an optional optimization and does not define a different public
namespace: without it, LAN users continue to use the Cloud edge. Each direct
Home route MUST use a Home-issued certificate for that same hostname and MUST
not bypass required authorization controls.

### Run WireGuard as a privileged GitOps DaemonSet

WireGuard is the initial private transport. A dedicated DaemonSet runs once on
each single-node cluster with `hostNetwork`, `/dev/net/tun`, and the minimum
host-network capabilities needed to create and manage the `wg0` interface. It
uses a pinned image containing the WireGuard tools. The Home peer maintains an
outbound relationship to the Cloud VPS, which avoids a home-router inbound
port-forward. The peers use distinct private tunnel addresses from a dedicated
range that does not overlap with either cluster's Pod, Service, or LAN CIDRs.
The DaemonSet applies only the required host firewall rules: WireGuard
establishment at Cloud and selected Home ingress ports from the tunnel and LAN.

The tunnel uses `172.31.255.0/30`: Cloud is `172.31.255.1`, Home is
`172.31.255.2`, and Cloud listens at `136.144.222.128:51820`. The permitted
Home LAN is `192.168.0.0/24`. The initial disposable acceptance hostname is
`edge-test.calcifer.tech`; its existing wildcard public answer already reaches
the Cloud VPS.

The tunnel is a narrow node-level transport for edge proxy traffic, not a
route for Kubernetes Pod or Service CIDRs. This avoids the existing overlapping
Pod CIDRs and makes the security boundary obvious. Submariner was rejected for
this change: it solves multi-cluster service networking and would require
Globalnet/NAT for the overlapping K3s ranges, despite the need being one
controlled reverse-proxy path.

WireGuard key pairs are generated without printing private values, then each
private key is stored in a cluster-specific SOPS-encrypted Kubernetes Secret.
Flux mounts the Secret into the DaemonSet. Peer public keys, endpoint,
allowed-route, and non-secret firewall configuration remain declarative in
Git. A manually managed host installation was rejected because it would drift
from Flux and break the repository's Kubernetes-only configuration boundary.

### Proxy Home ingress over TLS through a selectorless Cloud Service

Cloud Traefik reaches Home through a Kubernetes selectorless Service and
EndpointSlice whose endpoint is the Home WireGuard address. A Traefik
ServersTransport uses HTTPS, supplies the canonical SNI, and validates the
Home-issued Let's Encrypt certificate. The Home Traefik route receives the
canonical Host header and terminates the inner TLS connection.

This preserves encryption end to end, keeps the target endpoint visible to
Kubernetes/Traefik configuration, and avoids relying on an external DNS record
for a private tunnel address. A plain HTTP backend was rejected because the
tunnel alone would leave an accidental routing expansion without end-to-end
TLS protection.

### Install independent Home cert-manager resources using DNS-01

Home receives the same cert-manager chart versioning and staged Flux dependency
model used by Cloud: install CRDs/controller first, then apply DNS credentials
and staging/production ClusterIssuers. The DNS solver updates `_acme-challenge`
records in the public Azure DNS zone, so certificate issuance does not require
the Home node to be publicly reachable.

The existing repository SOPS age private key is manually installed as
`flux-system/sops-age` on Home before Flux decrypts Home secrets. Azure DNS
credentials are SOPS-encrypted and must be least-privilege; a distinct Azure
service principal is preferred even though it uses the shared SOPS key. Each
service receives an explicit certificate rather than a broadly shared wildcard
private key.

### Use CoreDNS with k8s-gateway for LAN DNS records

Home runs a dedicated CoreDNS instance with the `k8s-gateway` plugin, separate
from K3s CoreDNS. It is exposed to the LAN through a K3s LoadBalancer Service
on UDP and TCP port 53 at `192.168.0.102`. The plugin watches only
`externaldns.k8s.io` `DNSEndpoint` resources in the `calcifer.tech` zone.
Each Home-hosted service explicitly owns one `DNSEndpoint` whose A record
targets the Home static address.

For a matching zone name with no declared local record, CoreDNS falls through
to a direct `1.1.1.1` upstream; it never forwards queries back to the LAN
router. This means Cloud-hosted names such as `auth.calcifer.tech` retain their
public answer, while selected Home names resolve locally. The router is the
only DNS server advertised by DHCP, so clients do not bypass this decision by
choosing a public secondary resolver.

Pi-hole was rejected for this change because advertisement blocking, its UI,
and persistent query storage are not required for service placement. It can be
introduced later as an independent consumer of the same DNS policy if desired.

The LAN router has an accepted operational constraint: it cannot be configured
with Cloudflare as a DNS fallback while forwarding to the Home resolver. When
the Home resolver is unavailable, the router therefore returns DNS failure
instead of preserving general Internet resolution. This does not expose Home
services publicly, but it makes resolver availability a prerequisite for LAN
DNS operation and must be considered in monitoring and incident recovery.

### Make Home ingress deny-by-default for non-LAN traffic

Home does not create an Internet-facing DNS destination or router port-forward.
Its host firewall and Home Traefik entrypoints accept proxied service traffic
only from the WireGuard peer and direct traffic only from defined LAN CIDRs.
Application-specific authorization remains enforced on both routes; edge
authentication that exists only at Cloud must be replicated or direct routing
for that service must remain disabled.

## Risks / Trade-offs

- [Cloud or the tunnel fails] → Internet access to Home-hosted services is
  unavailable, while explicitly enabled LAN direct routes remain available.
- [A Home direct route bypasses a Cloud-only middleware] → require an
  equivalent Home control before enabling its LAN DNS override; otherwise keep
  the hostname routed through Cloud from every network.
- [Tunnel private keys or SOPS material leak] → never commit them, restrict
  RBAC and Secret mounts to the dedicated DaemonSet, rotate the affected
  peer/key, and revoke or replace encrypted credentials.
- [A privileged host-network DaemonSet damages node networking] → pin the
  image, grant only required capabilities and mounts, use a dedicated Service
  Account and namespace, verify its preflight behavior, and remove the
  DaemonSet to roll back its interface and firewall rules.
- [Tunnel addresses overlap an existing route] → select and validate a
  dedicated range before peer configuration; do not use K3s default Pod or
  Service ranges.
- [DNS-01 credential is overprivileged] → scope a dedicated Azure principal to
  the DNS zone and retain only its encrypted credential in Git.
- [Two clusters issue certificates for one hostname] → use explicit
  per-service Certificates, staging validation, and normal renewal schedules
  to stay within Let's Encrypt limits.
- [Cloud proxy cannot validate Home TLS] → validate the selectorless Service,
  EndpointSlice, ServersTransport SNI, and CA behavior with a disposable
  Home test backend before routing a production application.
- [The LAN DNS resolver is unavailable] → the router reports DNS failure because
  its configured fallback is unavailable; restore the Home resolver before
  relying on LAN DNS. This does not create a public path to Home.
- [DNS forwarding loops through the router] → configure CoreDNS forwarding
  directly to `1.1.1.1` and test both an explicit `DNSEndpoint` and an
  undeclared `calcifer.tech` name.

## Migration Plan

1. Record the dedicated tunnel CIDR, Cloud public endpoint, Home LAN CIDR, and
   permitted Home ingress ports; verify the required DaemonSet privileges and
   generate the WireGuard keys directly into SOPS-encrypted Secrets.
2. Provision the existing SOPS age key as `flux-system/sops-age` on Home and
   add the Home Flux roots for cert-manager installation and configuration.
3. Install cert-manager, apply the encrypted Azure DNS credential and both
   ClusterIssuers, then issue and verify a staging certificate followed by a
   production certificate for a test canonical hostname.
4. Deploy and verify the WireGuard DaemonSets and their host firewall rules.
   Confirm the Home node is reachable from Cloud only at the intended tunnel
   address and port.
5. Deploy the dedicated LAN CoreDNS/k8s-gateway service, then verify the router
   resolves an explicit Home `DNSEndpoint` locally and returns the public
   answer for undeclared names while Home is available. Record the accepted
   router constraint that an outage returns DNS failure, then restore the
   resolver.
6. Deploy a disposable Home test backend and Home Traefik route; create the
   Cloud selectorless Service, EndpointSlice, ServersTransport, and edge route.
   Verify TLS, Host preservation, denial of direct Internet access to Home,
   and the optional LAN-direct path.
7. Remove the disposable test workload or keep only a documented health check.
   Future workload-migration changes opt into the established route pattern.

Rollback removes the Cloud edge route and selectorless backend first, then the
Home route and test workload. It leaves cert-manager and the tunnel available
for subsequent work unless the tunnel itself is implicated in an incident;
then disable the peers and revoke/rotate their keys.
