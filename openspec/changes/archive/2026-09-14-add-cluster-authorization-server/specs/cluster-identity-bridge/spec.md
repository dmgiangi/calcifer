## MODIFIED Requirements

### Requirement: Cloud and Home expose one logical OIDC issuer
The authorization-server instances in `calcifer-cloud` and `calcifer-home` SHALL
publish the same explicit issuer, `https://auth.calcifer.tech`, and equivalent
issuer, JWKS, claim, and client contracts. Neither application authorization
logic nor token validation SHALL require a cluster-specific issuer. Stateful
endpoint selection is deployment transport configuration and is not inferred
from the issuer.

#### Scenario: Application discovers identity metadata from Cloud
- **WHEN** an application requests discovery through the public Cloud path
- **THEN** the response SHALL identify `https://auth.calcifer.tech` as issuer

#### Scenario: Application discovers identity metadata from Home LAN
- **WHEN** an application requests discovery through the Home LAN path
- **THEN** the response SHALL identify the same `https://auth.calcifer.tech`
  issuer

### Requirement: Canonical subjects are independent of provider and cluster
The server SHALL map every accepted authentication method to a stable
canonical subject. Google provider subjects, cluster names, and deployment
locations SHALL not be used as the application identity. Cloud and Home SHALL
emit equivalent subject and role claims for the same user.

#### Scenario: Google and password identify the same administrator
- **WHEN** the configured administrator authenticates once through Google and
  once through the local password method
- **THEN** both successful authorizations SHALL contain the same canonical
  subject and `admin` role

#### Scenario: Application validates a token from either cluster
- **WHEN** an application receives an unexpired token issued through either
  edge
- **THEN** it SHALL validate issuer, signature, audience, expiry, and roles
  without knowing which cluster issued it

### Requirement: Google authentication is optional to local availability
Both instances SHALL accept only the configured verified Google identity when
Google authentication is used. Both instances SHALL expose a password fallback
for the configured administrator, backed only by an Argon2id or bcrypt hash
from SOPS, so a Google outage does not prevent authentication.

#### Scenario: Home authenticates over LAN without Internet
- **WHEN** Home cannot reach Google and the administrator submits the correct
  local password over the LAN path
- **THEN** the server SHALL complete the requested OAuth/OIDC authorization
  with the canonical administrator subject and `admin` role

#### Scenario: Cloud authenticates while Home is unavailable
- **WHEN** the Home cluster or Home Internet path is unavailable and the
  administrator reaches a Cloud application
- **THEN** the Cloud instance SHALL independently complete Google or password
  authentication and issue a valid token

#### Scenario: Unconfigured Google identity attempts login
- **WHEN** Google returns a verified identity other than the configured user
- **THEN** authorization SHALL be denied without issuing a code or token

### Requirement: The login presentation is static and configuration-aware
The `/login` endpoint SHALL serve a versioned static HTML page with separate
CSS, JavaScript, and local Google icon assets. The page SHALL keep the Google
action available and SHALL display the password form only when local password
login is enabled. The password form SHALL retain CSRF protection.

#### Scenario: Password fallback is disabled
- **WHEN** a user opens the login page on an instance with local login disabled
- **THEN** the page SHALL show the Google action without a usable password form

#### Scenario: Password fallback is enabled
- **WHEN** a user opens the login page on an instance with local login enabled
- **THEN** the page SHALL load the protected password form and allow a valid
  password login without embedding the CSRF token in the static HTML

### Requirement: Tokens are interoperable and scoped
Cloud and Home SHALL use equivalent registered clients, audiences, scopes,
roles, token lifetimes, and signing material required for interoperability.
Issued JWTs SHALL contain issuer, subject, expiry, audience, scopes, and only
the configured authorization claims. They SHALL not contain cluster identity.

#### Scenario: Client credentials work against either edge
- **WHEN** a configured private client requests an allowed scope through
  either Cloud or Home
- **THEN** it SHALL receive a short-lived JWT accepted by the configured
  resource servers

#### Scenario: Unauthorized scope is requested
- **WHEN** a client requests a scope not registered for it
- **THEN** both instances SHALL deny the request without issuing a JWT

### Requirement: Identity secrets remain encrypted and equivalent
Signing keys, Google secrets, client secrets, and password hashes SHALL be
provided through SOPS-encrypted Secrets in both overlays. The stable signing
material SHALL remain available across restarts and SHALL never be logged or
emitted by diagnostics.

#### Scenario: One instance restarts
- **WHEN** either authorization-server instance restarts while a JWT is still
  valid
- **THEN** both issuer paths SHALL continue to expose JWKS data that validates
  that JWT

#### Scenario: Authentication activity is logged
- **WHEN** either instance logs authentication or token activity
- **THEN** it SHALL omit passwords, hashes, email labels, client secrets,
  authorization codes, access tokens, and signing-key contents

### Requirement: OAuth state locality is explicit
The v1 system SHALL keep an authorization-code flow and browser session on the
identity instance selected by the application's pinned endpoint profile. It
SHALL not claim replicated cross-cluster browser sessions or seamless
continuation after a path change.

#### Scenario: Normal Home application login
- **WHEN** a user starts and completes a Home application's OAuth flow through
  the Home endpoint profile
- **THEN** authorization and token exchange SHALL be handled through Home

#### Scenario: Path changes during an active flow
- **WHEN** an active OAuth flow changes endpoint profile
- **THEN** the flow MAY require restarting authentication rather than relying
  on unsynchronized in-memory state
