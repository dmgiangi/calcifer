## Why

`calcifer-cloud` has no cluster-owned identity boundary: Grafana uses a local
administrator credential and there is no standard way for workloads to obtain
short-lived credentials. A small authorization server is needed to bridge the
administrator's Google identity into cluster roles and to issue scoped machine
credentials, while allowing `calcifer-home` to keep working when the cloud or
the Internet is unavailable.

## What Changes

- Add a native Java 25 Spring Authorization Server deployed by Flux to
  `calcifer-cloud` at `https://auth.calcifer.tech`.
- Authenticate the cloud human administrator only through Google OIDC and map
  `dem.gianluigi@gmail.com` to the configured `admin` role; do not expose a
  cloud password-login path.
- Expose standard OIDC discovery, authorization, JWKS, user-info, and token
  endpoints, including the OAuth 2.0 `client_credentials` grant for explicitly
  configured technical clients and scopes.
- Configure Grafana at `grafana.calcifer.tech` to use the authorization server
  for browser OIDC login and to accept a valid machine token on its existing
  `/api` path through a protected Traefik ForwardAuth integration. Browser
  sessions must continue to work on the same hostname.
- Add a reusable, non-applied `calcifer-home` overlay that creates an
  independent issuer with its own keys, technical clients, Google registration,
  and an SOPS-encrypted local password-hash fallback for the sole home admin.
  The home fallback is intentionally disabled in cloud.
- Instrument the authorization server with Spring Boot Actuator Prometheus
  metrics and structured logs, add Grafana dashboard/query coverage, and bound
  its resources for the single-node cluster.
- Add a manually dispatched GitHub Actions release workflow that produces a
  Java 25 native image, publishes an immutable image to GHCR, and promotes the
  selected digest into Flux-managed cloud manifests.

## Capabilities

### New Capabilities

- `cluster-identity-bridge`: Federated Google login, static identity/role
  configuration, autonomous home password fallback, and standard OAuth2/OIDC
  token issuance.
- `grafana-authorization-server-integration`: Browser OIDC sign-in and scoped
  machine access to Grafana's existing API hostname through trusted ingress
  authentication.
- `authorization-server-observability`: Metrics, logs, dashboarding, resource
  bounds, and live health verification for the identity workload.
- `authorization-server-release-delivery`: Manually triggered native-image
  build, GHCR publication, and GitOps promotion of immutable releases.

### Modified Capabilities

None.

## Impact

- Adds a Java/Spring source module, native container build definition, tests,
  and GitHub Actions workflow.
- Adds Flux/Kustomize workload manifests, SOPS-encrypted technical secrets,
  Traefik middleware/route configuration, NetworkPolicies, and Grafana
  configuration/resources under the cloud cluster configuration.
- Establishes public cloud endpoints `auth.calcifer.tech` and the existing
  `grafana.calcifer.tech`; Google OAuth client registration and DNS/TLS
  configuration are required operational dependencies.
- Establishes a reusable home overlay but does not bootstrap, reconcile, or
  expose the not-yet-developed `calcifer-home` cluster.
- Replaces Grafana's normal cloud administrator sign-in path with Google OIDC
  after live acceptance has proved the replacement works. Existing Grafana
  service-account automation remains available only for internal provisioning,
  not as the external client-credential contract.
