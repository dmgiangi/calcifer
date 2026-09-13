## Context

`calcifer-cloud` is a single-node K3s cluster with roughly 4 GiB allocatable
memory. Its healthy Alloy/Thanos/Loki/Grafana stack already consumes about
2.9 GiB at runtime. Grafana is exposed at `https://grafana.calcifer.tech` but
has no external identity provider. `calcifer-home` is not bootstrapped yet.

The requested model has one Google-only cloud administrator,
`dem.gianluigi@gmail.com`, machine access with OAuth2 client credentials, and
an independent home issuer with a local administrator fallback for Internet
outages. Secrets must remain encrypted with SOPS and no plaintext credential
may be committed or emitted by diagnostics.

## Goals / Non-Goals

**Goals:**

- Deploy a compact Java 25 native Spring Authorization Server through cloud
  Flux and prepare a reusable, independent home overlay.
- Federate the configured cloud administrator with Google, issue standard
  scoped JWTs to technical clients, and support only the `admin` role.
- Integrate browser OIDC and machine-token API access with the existing
  `grafana.calcifer.tech` hostname.
- Reuse existing Alloy, Thanos, Loki, and Grafana observability; set explicit
  resource limits appropriate to the single node.
- Publish and promote native GHCR releases only from a manually dispatched
  GitHub Actions workflow.

**Non-Goals:**

- HA, dynamic user/client management, refresh tokens, persistent grant state,
  role management beyond `admin`, and self-service registration.
- Cloud password login, a second Grafana API hostname, or automatic trust by
  arbitrary cluster services.
- Bootstrapping or exposing the future home cluster before its DNS, TLS, and
  Flux configuration exist.

## Decisions

### Spring Authorization Server and native image

Create a Maven Java module using a Java 25-compatible Spring Boot version,
Spring Authorization Server, Spring Security OAuth2 Client, Actuator, and the
Prometheus registry. The release build produces a linux/amd64 GraalVM native
image that runs non-root with a read-only root filesystem.

Spring Authorization Server provides discovery, authorization, token, JWKS,
and user-info endpoints; Spring Security performs the Google login exchange. A
custom authority mapper accepts only the configured verified Google email and
maps it to `admin`. This is materially smaller than Keycloak on the current
node and avoids implementing protocol behavior ourselves.

### Independent issuers and static identity configuration

Cloud has explicit issuer `https://auth.calcifer.tech`. The home overlay
requires its own issuer/FQDN, signing key, Google OAuth registration, client
secrets, and Grafana client registration; it never shares cloud keys or claims
to be the cloud issuer. Common manifests are a base; overlays supply cluster
label, hostnames, issuer, client IDs, and Secret references. The home overlay
is rendered/tested but not placed in a Flux root yet.

Non-secret configuration declares permitted emails, role mappings, client IDs,
redirect URIs, scopes, grant types, and feature flags. SOPS Secrets provide
Google client secret, stable asymmetric signing key, technical-client secrets,
and, only for home, an Argon2id/bcrypt local-admin password hash. Cloud form
login is disabled. Home's successful local login maps to the same canonical
admin identity as its Google login. The plaintext password is never stored or
logged; an operator creates the hash locally before SOPS encryption.

The `grafana-api` private client is limited to `client_credentials` and scope
`grafana.api`. Tokens are short lived, have issuer/audience/expiry/scope and
only required role claims, and no refresh-token grant exists. One replica uses
in-memory authorization-code state. A restart can require a user to log in
again, but the stable signing key preserves issued-token validation. PostgreSQL
is deferred because of memory pressure; durable grants or replicas require a
future JDBC change.

### One Grafana hostname with OIDC plus ForwardAuth

Grafana is an authorization-code client of the cloud issuer, with the redirect
URI `https://grafana.calcifer.tech/login/generic_oauth`, PKCE, and scopes
`openid profile email`. Generic OAuth maps `admin` to Grafana organization
Admin. Its client secret is SOPS-encrypted. The cloud login form is disabled
only after live OIDC acceptance, while the existing encrypted Grafana admin
Secret remains rollback-only.

Grafana OSS does not validate arbitrary issuer JWTs for its HTTP API. A
higher-priority Traefik `IngressRoute` for the existing host and `/api` path
will therefore run this middleware chain:

1. remove caller-provided `X-WEBAUTH-*` headers;
2. call an internal authorization-server ForwardAuth endpoint;
3. reject invalid bearer tokens; for a valid `grafana.api` token, return named
   trusted identity and role headers; with no bearer token, return success with
   no headers so Grafana evaluates its own browser session;
4. forward only to the Grafana ClusterIP Service.

Grafana Auth Proxy consumes headers for machine requests while Generic OAuth
continues to own browser sessions. Explicit `/api` and `/` routes replace the
operator-created all-path ingress, retaining the current TLS secret and root
URL. NetworkPolicy limits Grafana ingress to Traefik and Auth Proxy's source
whitelist is set from the validated Traefik source address/CIDR. This avoids a
second public hostname and prevents header spoofing.

### Observability and resource envelope

Expose Actuator liveness/readiness and `/actuator/prometheus`; annotate the pod
so existing Alloy discovery scrapes it. Logs are automatically collected from
pods by Loki. Emit structured events for authentication, grants, and
ForwardAuth outcomes but never emails, passwords, authorization codes, access
tokens, or client secrets. Add an `Authorization Server` Grafana dashboard in
the existing Observability folder.

Begin with one replica, requests of 100m CPU/128Mi and limits of 500m CPU/384Mi.
Validate memory use live before increasing the allocation. Dashboard panels
cover availability, requests/errors, login/token outcomes, ForwardAuth
decisions, and resource use.

### Manually released, immutable GHCR images

A `workflow_dispatch`-only GitHub Actions workflow validates an explicit
version, builds/tests the module, produces the linux/amd64 native image,
publishes to GHCR, resolves its digest, and commits that digest to the cloud
Kustomize image reference. It has minimum `contents: write` and `packages:
write` permissions and a promotion concurrency lock. Flux deploys the commit;
there is no tag polling or automatic release trigger. Digest pinning makes the
Git revision identify the exact deployed binary, while GitHub runners avoid
native compilation pressure on K3s.

## Risks / Trade-offs

- [The node is already about 75% memory used] → one native replica, strict
  limits, live resource verification, and rollout halt on memory pressure.
- [In-memory authorization state is lost on restart] → short grants, no refresh
  tokens, documented re-login, and defer durable storage.
- [Google/cloud unavailable from home] → independent issuer plus local home
  password fallback; Google is optional for home availability.
- [Spoofed proxy headers grant Grafana access] → remove incoming headers, copy
  only named ForwardAuth response headers, restrict ingress with NetworkPolicy,
  configure whitelist, and test the negative path.
- [OAuth/ingress configuration locks out Grafana operators] → keep the local
  Grafana admin Secret only for rollback and verify both OIDC and machine API
  paths before disabling the cloud login form.
- [A secret is leaked] → SOPS-only storage, non-printing generation/validation,
  per-issuer keys, and documented rotation.

## Migration Plan

1. Provision DNS/TLS and Google OAuth clients outside Git; register the cloud
   callback and prepare a separate home callback for later.
2. Generate cloud signing, Google, Grafana browser-client, and machine-client
   secrets; encrypt them with SOPS. Prepare independent home equivalents and a
   home password hash outside the cloud Flux path.
3. Add/test the Java module, native build, manual GHCR workflow, cloud
   manifests, Grafana/Traefik policies, and non-applied home overlay. Render
   manifests and validate SOPS references without decrypting to output.
4. Manually publish and promote an immutable image. Flux deploys the
   authorization server, telemetry, Grafana OAuth config, and routes.
5. Verify readiness, metrics/logs/dashboard, Google login, and a
   client-credentials token querying both Thanos and Loki through
   `https://grafana.calcifer.tech/api/ds/query` without printing secrets.
6. Disable Grafana cloud password login only after all acceptance checks pass.

Rollback is a Git revert that restores prior Grafana ingress/auth settings and
removes the workload. Observability data remains unchanged; suspected secret
compromise also requires credential rotation.

## Open Questions

- Which FQDN, split-horizon DNS, and TLS issuer will home use?
- Who will generate and SOPS-encrypt the first home password hash?
- Does the GitHub identity that owns GHCR have permission to commit digest
  promotions to `master`?
