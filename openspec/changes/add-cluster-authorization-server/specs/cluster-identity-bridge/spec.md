## ADDED Requirements

### Requirement: Cloud federates only the configured Google administrator
The cloud authorization server SHALL use Google OIDC as its only human authentication mechanism. It SHALL accept `dem.gianluigi@gmail.com` only when Google verifies the identity, map it to `admin`, and reject every other identity. It SHALL not expose a cloud user/password login or persist a plaintext human credential.

#### Scenario: Configured Google identity completes authorization
- **WHEN** Google returns a verified identity for `dem.gianluigi@gmail.com`
- **THEN** the server SHALL establish an `admin` principal and continue the requested OAuth2 authorization flow

#### Scenario: Unconfigured Google identity attempts authorization
- **WHEN** Google returns a verified identity whose email is not configured
- **THEN** the server SHALL deny authorization without issuing a code or token

### Requirement: Authorization server exposes interoperable OIDC and OAuth2 endpoints
The authorization server SHALL publish an explicit HTTPS issuer, OIDC discovery metadata, authorization, token, JWKS, and user-info endpoints. Issued JWTs SHALL contain the configured issuer, expiry, audience, scopes, and only the configured role claims required by clients.

#### Scenario: OIDC client discovers issuer metadata
- **WHEN** an OIDC client requests the issuer discovery document
- **THEN** it SHALL receive endpoint and JWKS metadata whose issuer equals the configured explicit issuer URL

#### Scenario: Resource server verifies issued access token
- **WHEN** a resource server obtains the issuer JWKS and receives an unexpired issued JWT
- **THEN** it SHALL be able to verify its signature and issuer without a database lookup

### Requirement: Technical clients use scoped client credentials
The authorization server SHALL permit only statically configured private clients to use `client_credentials`. The Grafana machine client SHALL receive only a short-lived token containing `grafana.api` after authenticating with its current secret; it SHALL not receive a user identity or refresh-token privilege.

#### Scenario: Configured Grafana machine client obtains token
- **WHEN** the configured client authenticates with `grant_type=client_credentials` and requests `grafana.api`
- **THEN** the server SHALL return a short-lived JWT limited to that scope

#### Scenario: Client requests unauthorized scope
- **WHEN** a configured or unknown client requests a scope it is not permitted to use
- **THEN** the token endpoint SHALL deny the request without issuing a JWT

### Requirement: Home identity remains autonomous and has local fallback
The reusable home configuration SHALL define an issuer independent from cloud, with distinct signing and OAuth-client secrets. It SHALL enable local administrator password login only in the home overlay and map success to the same canonical identity and `admin` role used for home Google login. Cloud configuration SHALL disable this fallback.

#### Scenario: Home authenticates locally during Internet loss
- **WHEN** home cannot reach Google and its configured local administrator presents the correct password over TLS
- **THEN** it SHALL authenticate that administrator and complete the requested local OAuth2 authorization flow

#### Scenario: Cloud receives password-login attempt
- **WHEN** a caller attempts password login on the cloud issuer
- **THEN** the cloud authorization server SHALL reject it

### Requirement: Identity secrets are stable and encrypted
Signing keys, Google client secrets, technical-client secrets, and the home password hash SHALL be supplied from SOPS-encrypted Kubernetes Secrets. The server SHALL use a stable asymmetric signing key across pod restarts and SHALL not log or expose secrets, passwords, authorization codes, or access tokens.

#### Scenario: Pod restarts after issuing a token
- **WHEN** a server pod restarts while a JWT is unexpired
- **THEN** the issuer JWKS SHALL still validate that JWT signature

#### Scenario: Workload logs authentication activity
- **WHEN** authentication or token issuance is logged
- **THEN** logs SHALL omit passwords, email identities, client secrets, authorization codes, and access tokens
