## MODIFIED Requirements

### Requirement: Enabled Grafana instances use the canonical issuer
Every exposed Grafana instance SHALL use Generic OAuth/OIDC against
`https://auth.calcifer.tech` with authorization code and PKCE. A user carrying
the canonical `admin` role SHALL receive Grafana organization Admin access.
Grafana configuration SHALL not contain separate Cloud/Home issuers. The
current repository exposes Grafana only in Cloud; a future Home Grafana SHALL
reuse this same contract.

#### Scenario: Administrator signs in through an enabled edge
- **WHEN** the configured administrator signs in to an exposed Grafana
  instance through its reachable edge
- **THEN** Grafana SHALL establish an organization Admin session from the
  canonical OIDC claims

### Requirement: Grafana API accepts scoped machine credentials from either edge
The Grafana API SHALL accept a valid `client_credentials` JWT with
`grafana.api` on its existing hostname. The edge handling the request SHALL
validate issuer, signature, expiry, audience, and scope against the common
identity contract.

#### Scenario: Machine client queries Grafana
- **WHEN** the configured machine client sends a valid `grafana.api` bearer
  token to `/api/ds/query` through an available edge
- **THEN** Grafana SHALL return the permitted datasource query response

#### Scenario: Token lacks Grafana scope
- **WHEN** a caller sends a valid token without `grafana.api`
- **THEN** the edge SHALL reject it before Grafana processes the request

### Requirement: Trusted headers remain protected on enabled Grafana ingresses
Any ForwardAuth integration SHALL remove caller-provided `X-WEBAUTH-*`
headers, copy only named headers emitted after successful validation, and
limit direct Grafana Service access to trusted ingress traffic in every
overlay that exposes Grafana.

#### Scenario: Caller attempts header spoofing
- **WHEN** an unauthenticated caller supplies `X-WEBAUTH-*` headers
- **THEN** ingress SHALL remove the headers and the caller SHALL not gain
  Grafana access
