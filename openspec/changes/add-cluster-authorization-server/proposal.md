## Why

The two K3s clusters need one reusable identity contract for all applications.
Authentication must continue to work when the user reaches the public Cloud
edge, and from the Home LAN when Home has no Internet access. The previous
design used independent Cloud and Home issuers, which would force every
application to know which cluster issued a token and would produce different
user identifiers.

## What Changes

- Deploy the same compact authorization server to both `calcifer-cloud` and
  `calcifer-home`.
- Expose both instances through the canonical `https://auth.calcifer.tech`
  name: public DNS resolves to Cloud, while split-horizon LAN DNS resolves to
  Home.
- Use one logical issuer, one canonical user identifier space, and common
  roles/scopes. Applications SHALL not receive or depend on a Cloud/Home
  cluster identity.
- Support Google OIDC and a locally verified password fallback on both
  instances. Google is optional for a successful local password login.
- Keep signing keys, client registrations, and identity mapping equivalent on
  both clusters so tokens issued at either edge can be validated by either
  cluster.
- Keep OAuth/OIDC integration reusable: applications use the canonical issuer;
  ForwardAuth is reserved for applications such as Grafana that need an edge
  adapter.
- Retain the existing observability, resource bounds, SOPS handling, native
  image, and manual immutable release workflow, extending them to both
  overlays.

## Capabilities

### New Capabilities

- `cluster-identity-bridge`: Common Cloud/Home OIDC issuer, Google login,
  password fallback, canonical subjects, roles, scopes, and interoperable JWTs.
- `cluster-identity-routing`: Public and LAN resolution of the same identity
  endpoint and TLS/routing behavior for both access paths.
- `grafana-authorization-server-integration`: Browser OIDC sign-in and scoped
  machine access to Grafana's existing API hostname.
- `authorization-server-observability`: Metrics, logs, dashboards, resource
  bounds, and health verification for both identity workloads.
- `authorization-server-release-delivery`: Manual native-image release and
  digest-pinned promotion for both cluster overlays.

### Modified Capabilities

- `cloud-home-private-transit`: only if additional explicit edge routes are
  needed for identity validation or operational checks; the identity service
  SHALL not depend on the Cloud-to-Home tunnel for normal availability.
- `home-lan-dns`: declare the LAN answer for the canonical identity hostname.

## Impact

- The existing Java/Spring module remains the implementation baseline, but its
  principal mapping and local-login behavior must be made cluster-independent.
- Cloud and Home each receive an active authorization-server deployment and a
  valid TLS route for the same hostname.
- SOPS must supply equivalent identity material to both clusters. A shared
  signing key is the simplest v1 choice and increases the impact of a key
  compromise; rotation must therefore be coordinated.
- Applications get one issuer and one canonical `sub`, but the v1 design does
  not replicate browser sessions or OAuth authorization state between
  clusters. A user may need to authenticate again after changing access paths
  or during a failover.
- Cloud applications remain usable when Home or the Home Internet connection
  is unavailable. Home applications remain usable over the LAN when Cloud or
  the Internet is unavailable, using the password fallback.
