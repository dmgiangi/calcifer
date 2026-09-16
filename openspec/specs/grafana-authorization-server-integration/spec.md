# Grafana authorization server integration

## Purpose

Define reusable OIDC and ForwardAuth integration between Grafana and the
shared Cloud/Home authorization service.

## Requirements

### Requirement: Enabled Grafana instances use the canonical authorization hostname
Every exposed Grafana instance SHALL validate the canonical issuer
`https://auth.calcifer.tech` and use authorization code with PKCE. Its
authorization, token, and user-info URLs SHALL all use `auth.calcifer.tech`,
regardless of whether Grafana is deployed in Cloud or Home. The
`auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` OAuth endpoint
profiles SHALL not be used. A user carrying the canonical `admin` role SHALL
receive Grafana organization Admin access. Grafana configuration SHALL not
contain separate Cloud/Home issuers. During migration from a provider-specific
subject, the single-tenant Grafana instance SHALL use the verified email only
to adopt the existing account, while continuing to map the stable application
identity from `sub`.

#### Scenario: Administrator signs in through the canonical edge
- **WHEN** a user signs in to an enabled Grafana instance
- **THEN** browser authorization and Grafana token exchange SHALL both use `auth.calcifer.tech`, with split-horizon DNS selecting the appropriate reachable authorization-server instance

#### Scenario: Retired endpoint profile is configured
- **WHEN** a Grafana configuration contains `auth-cloud.calcifer.tech` or `auth-home.calcifer.tech` as an authorization, token, or user-info endpoint
- **THEN** the configuration SHALL be rejected during validation and SHALL not be deployed

#### Scenario: Existing Grafana account is adopted after subject migration
- **WHEN** the configured verified administrator authenticates with canonical
  subject `user:admin` and an existing Grafana account has the same email
- **THEN** Grafana SHALL associate the OAuth identity with that account instead
  of failing user synchronization

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
