## ADDED Requirements

### Requirement: Grafana browser sign-in uses cloud authorization server
Grafana at `https://grafana.calcifer.tech` SHALL use Generic OAuth/OIDC against the cloud authorization server with authorization code and PKCE. A user carrying `admin` SHALL receive Grafana organization Admin access. The cloud Grafana password-login form SHALL be disabled only after successful live OIDC acceptance.

#### Scenario: Administrator signs in through Google
- **WHEN** the configured administrator opens Grafana and completes Google login through `https://auth.calcifer.tech`
- **THEN** Grafana SHALL establish an organization Admin session from OIDC claims

#### Scenario: Unconfigured identity reaches Grafana callback
- **WHEN** an unauthorized identity attempts Grafana sign-in
- **THEN** Grafana SHALL not receive a successful authorization response or create a session for that identity

### Requirement: Grafana API accepts scoped machine credentials on existing hostname
The existing `grafana.calcifer.tech` hostname SHALL accept a valid `client_credentials` JWT with `grafana.api` for `/api` requests. Traefik SHALL validate it through an internal authorization-server ForwardAuth endpoint and Grafana Auth Proxy SHALL receive trusted machine identity and role headers. A valid request SHALL be able to use Grafana's datasource query API according to its mapped Grafana role.

#### Scenario: Machine client queries datasource
- **WHEN** the Grafana machine client sends a valid `grafana.api` bearer token to `/api/ds/query`
- **THEN** Traefik SHALL authenticate it and Grafana SHALL return the allowed datasource query response

#### Scenario: Bearer token lacks Grafana scope
- **WHEN** a caller sends a valid JWT without `grafana.api` to Grafana `/api`
- **THEN** ForwardAuth SHALL reject the request before Grafana processes it

### Requirement: Browser sessions and trusted headers remain protected
The `/api` route SHALL preserve valid Grafana browser-session behavior when no bearer token is supplied. It SHALL remove caller-supplied auth-proxy headers before ForwardAuth, copy only named headers emitted after successful machine validation, and limit direct Grafana Service access to trusted ingress traffic.

#### Scenario: Browser dashboard request uses Grafana session
- **WHEN** a signed-in browser requests an `/api` endpoint without a bearer token
- **THEN** it SHALL reach Grafana without machine headers and Grafana SHALL evaluate its existing session

#### Scenario: Caller attempts auth header spoofing
- **WHEN** an unauthenticated caller supplies `X-WEBAUTH-*` headers
- **THEN** ingress SHALL remove the headers and the caller SHALL not gain Grafana access
