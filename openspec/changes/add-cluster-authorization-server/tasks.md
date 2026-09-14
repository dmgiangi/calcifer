## 1. Architecture and prerequisites

- [x] 1.1 Inspect Cloud/Home networking, DNS, TLS, resource headroom, and
  existing authorization-server implementation.
- [x] 1.2 Decide and document the canonical identity contract: issuer,
  canonical subject format, roles, scopes, audiences, and the v1 limitation on
  cross-cluster session state.
- [x] 1.3 Prepare public DNS for `auth.calcifer.tech` to Cloud and split-horizon
  LAN DNS for the same name to Home.
- [x] 1.4 Provision valid TLS certificates for `auth.calcifer.tech` in both
  clusters and document renewal behavior during a Home Internet outage.
- [x] 1.5 Confirm the password fallback exposure policy, rate limiting, and
  operator recovery procedure for both ingress paths.
- [x] 1.6 Add and verify Cloud- and Home-pinned OAuth endpoint profiles and
  their DNS/TLS/ingress routes; both endpoint certificates and live routes
  are Ready on the new revision.
- [ ] 1.7 Register the Cloud and Home callback URIs in the configured Google
  OAuth client.

## 2. Common authorization-server implementation

- [x] 2.1 Retain the Java 25 Spring Authorization Server module, standard OIDC
  endpoints, native-image direction, and explicit configuration model.
- [x] 2.2 Change Google and local authentication to resolve to the same
  canonical administrator subject and `admin` role.
- [x] 2.3 Enable and test password fallback on both Cloud and Home without
  storing or logging the plaintext password; both overlays are enabled, their
  encrypted hashes are equivalent, and the native login smoke test passes.
- [x] 2.4 Configure equivalent static clients, redirect URIs, audiences, grant
  types, scopes, token lifetimes, and role claims in both profiles.
- [x] 2.5 Use interoperable stable signing material and verify tokens issued by
  either instance against both JWKS endpoints; two native instances issued
  machine tokens that validated against both local JWKS responses.
- [x] 2.6 Ensure discovery metadata and all token claims are identical in
  contract and contain no cluster identity.
- [x] 2.7 Make OAuth authorization state locality explicit and test that a
  normal flow remains on one edge; document re-login after a path change.
- [x] 2.8 Add negative tests for unknown Google identities, bad passwords,
  unauthorized clients/scopes, invalid issuer/audience/signature, expired
  tokens, and spoofed proxy headers.
- [x] 2.9 Add Actuator health groups, Prometheus metrics, structured
  secret-safe authentication events, and native-image integration tests.

## 3. Container and release delivery

- [x] 3.1 Add a reproducible non-root, read-only-root-filesystem native image
  container for linux/amd64 and verify health/discovery startup.
- [x] 3.2 Keep the manually dispatched GitHub Actions build/test/native-image
  workflow with minimum package permissions.
- [x] 3.3 Update release promotion to write the same digest to Cloud and Home,
  with concurrency control and validation before either file changes.

## 4. Cloud and Home GitOps deployment

- [x] 4.1 Retain reusable base resources for namespace, ServiceAccount,
  Deployment, Service, probes, metrics annotations, security context, resource
  bounds, and NetworkPolicy.
- [x] 4.2 Update the Cloud overlay to use the common issuer, common identity
  contract, and public canonical route.
- [x] 4.3 Activate the Home overlay with the same issuer, local canonical route,
  local password fallback, TLS configuration, and independent Flux inclusion;
  live reconciliation remains part of the acceptance checks.
- [x] 4.4 Create equivalent SOPS-encrypted Secrets for signing material, Google
  registration, OIDC clients, machine clients, and password hash in both
  clusters without emitting plaintext.
- [x] 4.5 Render both overlays and verify equivalent issuer/client/claim
  configuration, valid Secret references, selectors, and policies.

## 5. Reusable application and Grafana integration

- [x] 5.1 Define and document the application integration contract: canonical
  issuer, discovery, client registration, scopes, audiences, roles, and
  canonical subject.
- [ ] 5.2 Update Grafana Cloud integration to validate the canonical issuer,
  use the Cloud-pinned endpoints, and verify browser OIDC login through the
  Cloud path from public Internet and LAN.
- [x] 5.3 Document the reusable Grafana integration for any future Home
  exposure; Home currently has no Grafana ingress and must not introduce a
  second issuer.
- [x] 5.4 Apply protected `/api` ForwardAuth routes and NetworkPolicies to each
  Grafana ingress that is enabled; static rendering and controller tests cover
  bearer-less browser sessions, valid machine tokens, invalid tokens, and
  missing scopes. Live spoof-header behavior remains an acceptance check.
- [x] 5.5 Disable local Grafana password login only after the applicable OIDC
  and machine-token acceptance checks succeed.

## 6. Observability and acceptance

- [x] 6.1 Provision dashboard panels and alerts for both authorization-server
  instances, including availability, errors, login/token/password outcomes,
  ForwardAuth decisions, and resource use.
- [x] 6.2 Verify Alloy and Loki collection independently for Cloud and Home,
  including the no-sensitive-data guarantee.
- [ ] 6.3 Verify Home LAN password login while Internet/Google is unavailable.
- [x] 6.4 Verify Cloud authentication and Cloud application access while Home
  is unavailable.
- [x] 6.5 Verify tokens issued through Cloud and Home have the same issuer,
  canonical subject, roles, scopes, and cross-cluster JWKS validation.
- [x] 6.6 Obtain a machine token through each edge without printing it and use
  it against the permitted Grafana datasource API.
- [x] 6.7 Record safe validation, certificate renewal, key rotation, password
  recovery, and symmetric rollback procedures.
- [ ] 6.8 Mark the change complete only after both clusters reconcile and all
  acceptance checks pass without memory or availability regressions.
