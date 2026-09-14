## 1. Network and secret prerequisites

- [x] 1.1 Select and document a dedicated WireGuard tunnel CIDR that does not overlap the Cloud/Home Pod CIDRs, Service CIDRs, or Home LAN CIDRs.
- [x] 1.2 Identify the Cloud public WireGuard endpoint, the LAN CIDRs permitted for direct Home access, and the initial non-production canonical hostname used for acceptance testing.
- [x] 1.3 Verify the Home and Cloud nodes support the required host-network DaemonSet mounts, `/dev/net/tun`, WireGuard kernel/userspace operation, and minimum security capabilities before deploying a tunnel.
- [x] 1.4 Generate distinct WireGuard key pairs without printing private material, encrypt each private key in a cluster-specific SOPS Secret, and record only public peer details in Git.
- [x] 1.5 Provision the existing repository SOPS age private key as `flux-system/sops-age` on `calcifer-home` without printing or committing it.
- [x] 1.6 Create and scope a dedicated Azure DNS client required by Home cert-manager; encrypt its Kubernetes Secret with SOPS without committing plaintext values.

## 2. Home certificate-management GitOps

- [x] 2.1 Add the `clusters/calcifer-home` Kustomize and Flux roots needed to reconcile infrastructure and enforce cert-manager installation before its configuration.
- [x] 2.2 Add the pinned cert-manager HelmRepository, namespace, and HelmRelease for Home using the Cloud installation pattern.
- [x] 2.3 Add SOPS-encrypted Azure DNS credentials plus staging and production DNS-01 ClusterIssuers for `calcifer.tech`.
- [x] 2.4 Reconcile the Home certificate-management Kustomizations and verify the controller, both issuers, and SOPS decryption are Ready.
- [x] 2.5 Issue and verify staging then production Certificates for the selected Home test hostname without publishing a direct Home Internet endpoint.

## 3. Private Cloud-to-Home transport

- [x] 3.1 Add pinned, dedicated WireGuard DaemonSets with `hostNetwork`, minimum required capabilities/mounts, SOPS-encrypted key mounts, and node scheduling for both clusters.
- [x] 3.2 Configure the DaemonSet peers so Home maintains an outbound encrypted connection to Cloud and only the dedicated tunnel range is routed.
- [x] 3.3 Apply declarative host firewall rules through the DaemonSets that admit WireGuard establishment at Cloud, permit only selected Home ingress ports from the tunnel and LAN, and add no Home-router Internet port-forward.
- [ ] 3.4 Verify the tunnel handshake, bidirectional reachability of only the intended tunnel endpoints, and absence of routes to either remote Kubernetes Pod or Service CIDR.

## 4. LAN DNS

- [x] 4.1 Add the ExternalDNS `DNSEndpoint` CRD and a pinned CoreDNS/k8s-gateway deployment with RBAC limited to `DNSEndpoint` records in the intended zone.
- [x] 4.2 Expose the dedicated resolver through a K3s LoadBalancer Service on `192.168.0.102` for both UDP and TCP port 53, with a direct `1.1.1.1` upstream and no forwarding loop to the LAN router.
- [x] 4.3 Add a non-production Home `DNSEndpoint` and verify that queries sent to the LAN router resolve it to `192.168.0.102` while undeclared `calcifer.tech` names retain their public Cloud answer.
- [ ] 4.4 Verify the router's Cloudflare fallback preserves general Internet resolution when the Home resolver is deliberately unavailable, then restore the resolver.

## 5. Cloud edge and Home ingress routing

- [ ] 5.1 Deploy a disposable, non-sensitive Home test backend and an HTTPS Home Traefik route for the selected canonical hostname, including its explicit certificate and required access control.
- [ ] 5.2 Add a selectorless Service and EndpointSlice in Cloud targeting the Home WireGuard address, plus a Traefik ServersTransport that validates the Home certificate with canonical SNI.
- [ ] 5.3 Add the Cloud Traefik HTTPS route for the test hostname, preserving Host and forwarded HTTPS metadata while proxying through the tunnel.
- [ ] 5.4 Configure the public DNS record for the test hostname to resolve to the Cloud VPS and the matching Home `DNSEndpoint` only after equivalent Home access controls are verified.
- [ ] 5.5 Reconcile the Cloud and Home resources with Flux and verify every related Kustomization, HelmRelease, Certificate, Service, EndpointSlice, DNS service, and Traefik route is Ready.

## 6. End-to-end verification and handoff

- [ ] 6.1 Verify an Internet client outside the LAN reaches the test hostname through Cloud, Cloud validates the Home TLS certificate, and Home receives the original Host header.
- [ ] 6.2 Verify a public Internet client cannot reach the Home node directly and that the Home router has no inbound forwarding rule for the test service.
- [ ] 6.3 Verify LAN DNS resolves the enabled test hostname directly to Home, then verify valid TLS and equivalent authorization through Home.
- [ ] 6.4 Verify `auth.calcifer.tech` and `grafana.calcifer.tech` retain their existing Cloud behavior before and after the edge-route deployment.
- [ ] 6.5 Verify tunnel failure prevents only public Home-service access while no unauthorized direct path is created, then restore the tunnel and confirm recovery.
- [ ] 6.6 Remove the disposable test backend and its edge route, or document and protect a retained operational health endpoint; record the approved route pattern for the later observability migration.

## 7. External operations guide

- [ ] 7.1 Create a secret-free operator guide for all prerequisites and operations that cannot be reconciled from this repository: Cloud VPS and Home-server baseline/host networking, K3s installation and kubeconfig access, Flux bootstrap and SOPS age-key provisioning, WireGuard/DaemonSet preflight and key rotation, router DHCP/upstream-DNS configuration and recovery, Azure DNS service-principal/RBAC setup, public DNS prerequisites, verification commands, rollback, and incident recovery. The guide SHALL identify manual steps, required privileges, and Secret locations by name without containing credentials, private keys, tokens, or plaintext secret values.
