## 1. Authorization Server client model

- [x] 1.1 Extend `IdentityProperties` with declarative client definitions while preserving the existing Grafana configuration contract.
- [x] 1.2 Implement conversion of declarative definitions into `RegisteredClient` instances, including grant types, authentication methods, redirect URIs, scopes, PKCE and token TTL.
- [x] 1.3 Update token claim customization to resolve configurable audience and retain existing Grafana subject and role behavior.
- [x] 1.4 Add unit tests for authorization-code clients, client-credentials clients, secret resolution, invalid definitions and backward-compatible Grafana claims.

## 2. Authorization Server Kubernetes configuration

- [x] 2.1 Add the external client YAML ConfigMap and configure Spring to import the mounted file.
- [x] 2.2 Add the Homepage client definition with canonical callback, OIDC scopes, authorization-code grant and PKCE.
- [x] 2.3 Add the Homepage OIDC client secret to the SOPS secret example and wire it into the Authorization Server deployment without exposing its value.
- [x] 2.4 Configure rollout/checksum behavior so client ConfigMap or Secret changes restart the Authorization Server.

## 3. Homepage manifests and catalog

- [x] 3.1 Create `clusters/apps/homepage/base` with the Homepage workload, service, ConfigMaps, secret references and shared `services.yaml` catalog.
- [x] 3.2 Add Cloud and Home overlays with equivalent deployment behavior and environment-specific ingress/TLS configuration for `calcifer.tech`.
- [x] 3.3 Configure Authorization Server and Grafana catalog entries with canonical links and HTTP `siteMonitor` checks.
- [x] 3.4 Configure Homepage native OIDC variables, issuer, client ID, callback behavior and session secret references.
- [x] 3.5 Add Homepage to the appropriate cluster Kustomizations and verify it does not require Kubernetes API RBAC.

## 4. Validation and rollout

- [x] 4.1 Run Authorization Server unit tests and build checks, then validate the generated native image configuration if applicable.
- [x] 4.2 Validate Kustomize rendering for both Cloud and Home overlays and inspect rendered manifests for secrets, hostname and rollout wiring.
- [x] 4.3 Verify OIDC login, split-horizon LAN access and HTTP monitors in both cluster environments.
- [x] 4.4 Document operational steps for adding a future Homepage service and a future declarative OIDC client.
