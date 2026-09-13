## 1. Prerequisites and project setup

- [x] 1.1 Verify the cloud node architecture, available resource headroom, Traefik source addressing, GitHub package ownership, and Actions permissions required for a linux/amd64 GHCR release.
- [x] 1.2 Provision `auth.calcifer.tech` DNS/TLS prerequisites and create the cloud Google OAuth client with redirect URI `https://auth.calcifer.tech/login/oauth2/code/google`; keep generated credentials outside Git.
- [x] 1.3 Create the Java/Maven authorization-server module with Java 25, Spring Authorization Server, OAuth2 Client, Actuator, Prometheus registry, test dependencies, and native-image build configuration.
- [x] 1.4 Add application configuration model, configuration validation, and documentation for issuer, static users/roles, registered clients, grants, scopes, token lifetime, and local-login feature flag.

## 2. Authorization server implementation

- [x] 2.1 Configure Spring Authorization Server endpoints, explicit issuer, stable asymmetric JWK source, JWT claims, discovery metadata, user-info, and short-lived access tokens without refresh tokens.
- [ ] 2.2 Implement Google OAuth2 login and an authority mapper that accepts only verified `dem.gianluigi@gmail.com` and maps it to `admin`; add negative authorization tests for other identities.
- [ ] 2.3 Configure static private clients, including a `grafana-api` client limited to `client_credentials` and `grafana.api`; add grant, scope-denial, token-claim, and JWKS verification tests.
- [x] 2.4 Implement the internal Traefik ForwardAuth endpoint: validate issuer/signature/expiry/audience/scope, reject invalid bearer tokens, return only named Grafana identity/role headers for valid machine tokens, and pass bearer-less browser-session requests without headers.
- [ ] 2.5 Implement home-only local administrator password authentication using Argon2id or bcrypt hash configuration, map it to the canonical admin identity, and test that the cloud profile rejects password login.
- [ ] 2.6 Configure Actuator health groups, Prometheus exposure, structured secret-safe security events, liveness/readiness probes, and native-image integration tests.

## 3. Native image and manual release delivery

- [ ] 3.1 Add a reproducible non-root, read-only-root-filesystem native container build for linux/amd64 and verify the resulting image starts and serves health/discovery endpoints.
- [x] 3.2 Add a `workflow_dispatch` GitHub Actions workflow that validates release-version input, runs tests, builds the native image, and publishes an immutable GHCR artifact with minimum required permissions.
- [x] 3.3 Add workflow digest resolution, promotion concurrency control, and a commit that updates the cloud Kustomize image reference to the immutable digest; verify an invalid input cannot publish or promote.

## 4. Cloud GitOps deployment and secret handling

- [x] 4.1 Add reusable Kustomize base resources for namespace, ServiceAccount, Deployment, Service, ConfigMap, probes, pod metrics annotations, security context, explicit resource bounds, and NetworkPolicies.
- [x] 4.2 Add the `calcifer-cloud` overlay and Flux inclusion for `auth.calcifer.tech`, production cert-manager/Traefik TLS configuration, cloud issuer values, and digest-pinned image reference.
- [x] 4.3 Prepare SOPS Secret templates and a non-printing operator procedure for the cloud signing key, Google client secret, Grafana browser client secret, and Grafana machine client secret; generate/encrypt real values without committing plaintext.
- [x] 4.4 Render the cloud Kustomization, validate Secret references and policy selectors, and confirm no sensitive generated value appears in Git, command output, or logs.

## 5. Grafana and Traefik integration

- [x] 5.1 Register Grafana as an authorization-code/PKCE client and configure the Grafana Operator resource for Generic OAuth discovery, role mapping, SOPS-sourced client secret, and the existing root URL.
- [x] 5.2 Enable Grafana Auth Proxy for only trusted forwarded identities, configure its source whitelist from verified Traefik addressing, and retain the existing encrypted Grafana admin Secret for rollback.
- [x] 5.3 Replace the operator-created Grafana all-path ingress with TLS-equivalent explicit Traefik routes: prioritized `/api` with header-scrub plus ForwardAuth middleware and `/` for normal browser flow.
- [ ] 5.4 Add NetworkPolicies that permit Grafana only from Traefik and permit the internal ForwardAuth call plus Alloy scraping; test valid browser session, valid machine token, absent token, invalid token, and spoofed header behavior.
- [ ] 5.5 Disable the cloud Grafana password-login form only after browser OIDC and client-token API acceptance succeeds.

## 6. Home autonomy preparation

- [x] 6.1 Add a non-applied `calcifer-home` overlay with independent issuer, signing-key/client Secret references, cluster labels, ingress placeholders, and Grafana client configuration; do not add it to a Flux root.
- [x] 6.2 Document the required future home FQDN, split-horizon DNS, TLS issuer, distinct Google callback/client, and the trust implications of separate cloud/home issuers.
- [x] 6.3 Provide an operator-local procedure to generate and SOPS-encrypt the initial home admin password hash without revealing the plaintext; render-test the home overlay with non-secret placeholders.

## 7. Observability and acceptance

- [x] 7.1 Add a Grafana Operator dashboard in the existing Observability folder for authorization-server availability, HTTP errors/latency, login/token/ForwardAuth outcomes, and CPU/memory usage.
- [ ] 7.2 Verify Alloy sends authorization-server metrics to Thanos and Loki collects its structured logs without sensitive labels or fields.
- [ ] 7.3 Manually dispatch an initial GHCR release, confirm Flux reconciliation, inspect workload/resource readiness, and stop or resize before proceeding if the node experiences memory pressure.
- [ ] 7.4 Perform interactive Google login to `grafana.calcifer.tech` as `dem.gianluigi@gmail.com` and verify Grafana organization Admin access.
- [ ] 7.5 Obtain a `grafana.api` client-credentials token without printing it and use it against `https://grafana.calcifer.tech/api/ds/query` to confirm successful Thanos and Loki queries.
- [ ] 7.6 Record the safe validation and rollback procedures; mark the change complete only after all live checks pass.
