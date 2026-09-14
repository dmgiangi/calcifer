## MODIFIED Requirements

### Requirement: Enabled Grafana instances use the canonical issuer
Every exposed Grafana instance SHALL validate the canonical issuer
`https://auth.calcifer.tech` and use authorization code with PKCE. Its
authorization, token, and user-info URLs SHALL use the endpoint profile pinned
to Grafana's deployment: Cloud Grafana uses `auth-cloud.calcifer.tech`; a
future Home Grafana uses `auth-home.calcifer.tech`. A user carrying the
canonical `admin` role SHALL receive Grafana organization Admin access.
Grafana configuration SHALL not contain separate Cloud/Home issuers.

#### Scenario: Administrator signs in through an enabled edge
- **WHEN** a LAN browser signs in to Cloud Grafana
- **THEN** browser authorization and Cloud Grafana token exchange SHALL both
  use `auth-cloud.calcifer.tech`, and Grafana SHALL establish an organization
  Admin session from canonical OIDC claims

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
