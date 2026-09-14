## Context

`calcifer-cloud` and `calcifer-home` are separate K3s failure domains. Cloud
has the public edge; Home has the LAN DNS resolver and must remain usable when
its Internet connection is unavailable. The identity service is a shared
platform dependency, so applications must not need separate Cloud/Home issuer
configuration.

The previous design made the issuers independent. That provided isolation but
did not provide a transparent identity contract. This change keeps the
existing compact Java 25 Spring Authorization Server baseline and changes its
deployment and identity model.

## Goals / Non-Goals

**Goals:**

- Make `https://auth.calcifer.tech` the only issuer configured by applications.
- Run an equivalent authorization-server instance in each cluster.
- Allow Google OIDC when reachable and password login when Google is not
  reachable, including during a Home LAN-only outage.
- Make the canonical user subject and authorization claims independent of the
  authentication method and cluster.
- Keep the implementation static and small: configured users, clients, roles,
  scopes, and encrypted secrets; no self-service administration.
- Preserve Grafana OIDC, machine access, observability, GitOps, and immutable
  releases.

**Non-Goals:**

- Active-active replication of browser sessions, authorization codes, grants,
  or revocation state in v1.
- Seamless continuation of an in-progress OAuth flow after the request moves
  from one edge to the other.
- Dynamic user/client management, refresh tokens, HA databases, or global
  logout across a network partition.
- Exposing cluster location as an identity claim or requiring applications to
  accept `cloud` and `home` as separate issuers.

## Decisions

### One logical issuer, two local instances

Both instances publish the same issuer:

```text
https://auth.calcifer.tech
```

Public DNS sends Internet clients to Cloud Traefik. Home split-horizon DNS
sends LAN clients to Home Traefik. Each ingress terminates TLS and forwards to
the local authorization-server Service; normal authentication does not depend
on the Cloud/Home WireGuard tunnel.

Both routes use the same hostname and certificate identity. Home certificate
issuance/renewal must be prepared while connectivity is available, and the
resulting Secret must remain valid during a temporary Internet outage.

The routing contract is:

```text
                         public DNS
Internet ───────────────▶ Cloud Traefik ─▶ Cloud auth server

LAN client ── split DNS ─▶ Home Traefik  ─▶ Home auth server
```

Applications use only the canonical issuer. Cluster-local DNS and ingress
provide locality without exposing that choice to application configuration.

### Canonical identity and authorization claims

Google's provider-specific `sub` is not used as the application identity,
because a password login must resolve to the same user. The server maps every
accepted authentication method to a configured canonical subject, initially an
opaque stable identifier such as `user:admin`.

Tokens contain the issuer, subject, audience, expiry, scope, and configured
roles/groups. They do not contain `cloud`, `home`, or a cluster-dependent user
ID. Applications map the common roles/groups to their local authorization
model; app-specific access uses scopes and audiences.

The same static identity and client configuration is rendered to both
clusters. SOPS-encrypted secrets are stored separately in each cluster's
overlay but contain equivalent values where interoperability requires it.

### Google and password authentication

Both instances register Google OIDC and accept only the configured verified
Google identity. Both also expose the local password fallback for the
canonical administrator. The password is represented only by an Argon2id or
bcrypt hash and is supplied from SOPS.

The login page presents both methods. It does not wait for a Google timeout
before offering the password path. Rate limiting and safe authentication
events are required because the fallback is available at the public Cloud
edge as well as from the LAN.

The two instances do not share live login sessions. A session created on one
instance is therefore not assumed to exist on the other. The local routing
rule keeps ordinary flows on one instance; a path change or failover may
require a new login.

### Signing keys, JWKS, and token validation

The simplest v1 interoperability model uses the same stable asymmetric
signing key in both clusters and publishes the same JWKS metadata. Tokens from
either instance can then be validated by applications in either cluster
without a database lookup.

The key is duplicated only through SOPS-encrypted Secrets and never in
plaintext. Rotation is a coordinated operation: publish the new key while
retaining the old public key, update both instances, verify validation, then
retire the old key. A later version may use separate signing keys and a
combined JWKS, but that is deliberately outside the minimal implementation.

### OAuth state and locality limitation

JWT validation is stateless, but authorization-code exchange and browser
sessions are not. The v1 contract requires an OAuth flow to remain on the
identity instance selected by its access path. Cloud applications use the
Cloud path and Home applications use the Home path during normal operation.

If a future requirement demands completely seamless SSO while a browser moves
between independently reachable Cloud and Home paths, the change must add a
shared durable state store or a carefully reviewed stateless authorization
code/session design. It must not silently rely on two in-memory stores.

### Reusable application integration

OIDC-capable applications configure the canonical issuer and their own client
registration. They consume canonical subjects, roles, groups, audiences, and
scopes only. Applications that cannot validate OIDC use a controlled edge
adapter such as the existing Grafana ForwardAuth integration; the adapter
validates issuer, signature, audience, expiry, and scope and strips spoofable
headers.

### Observability and releases

The existing native Java image, non-root/read-only container, Actuator
metrics, structured secret-safe logs, Grafana dashboard, and manual GHCR
workflow remain the baseline. Health, metrics, logs, and release acceptance
must be verified independently for Cloud and Home. The promotion must update
both digest-pinned overlays atomically or fail before changing either one.

## Risks / Trade-offs

- [A shared signing key increases blast radius] → protect it with SOPS,
  restrict access, coordinate rotation, and document the later multi-key
  option.
- [Browser state is not replicated] → keep flows local, accept re-login after
  path changes, and do not advertise cross-site failover as seamless SSO.
- [Public password fallback increases attack surface] → use a strong hash,
  rate limiting, safe failure responses, and secret-safe audit events.
- [Two deployments can drift] → render both overlays in CI and test issuer,
  discovery, client, role, and key equivalence.
- [Home TLS renewal needs connectivity] → provision before outage and monitor
  certificate expiry locally.
- [Google is unreachable] → the local password method remains independent of
  Google and is tested on both ingress paths.

## Migration Plan

1. Update the existing authorization-server configuration model and tests to
   use canonical subjects, common issuer values, and password fallback on both
   profiles.
2. Prepare public and split-horizon DNS, TLS certificates, and equivalent
   SOPS-encrypted identity material for Cloud and Home.
3. Activate the Home overlay and expose the same canonical hostname locally;
   keep the Cloud overlay as the independent public instance.
4. Validate discovery, Google login, password login, JWKS, canonical claims,
   and client credentials independently through each edge.
5. Integrate Grafana and subsequent applications against only the canonical
   issuer. Verify both browser and machine paths.
6. Release and promote the same immutable image digest to both overlays, then
   verify reconciliation and resource headroom in both clusters.

Rollback is a Git revert of the affected overlays and application OIDC
configuration. If the shared signing key or password hash is suspected to be
compromised, rotate it in both clusters before restoring service.

## Resolved assumptions

- The initial canonical user is only `dem.gianluigi@gmail.com`, mapped to the
  opaque subject `user:admin`.
- Password fallback is available through both public Cloud and LAN Home paths;
  rate limiting and coordinated recovery are mandatory safeguards.
- Re-login after switching between Cloud and Home is acceptable for v1 because
  browser sessions and authorization-code state are deliberately local.
- Home uses the existing `letsencrypt-production-azure` ClusterIssuer and its
  Azure DNS solver; renewal must occur while external connectivity is available.
