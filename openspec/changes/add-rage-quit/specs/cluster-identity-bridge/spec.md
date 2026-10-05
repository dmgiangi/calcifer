# Cluster identity bridge delta

## MODIFIED Requirements

### Requirement: Canonical subjects are independent of provider and cluster
The server SHALL map every accepted authentication method to a stable canonical subject. Google provider subjects, cluster names, application names, and deployment locations SHALL NOT be used as the person identity. Cloud and Home SHALL emit equivalent subject and role claims for the same user. The configured administrator SHALL retain `user:admin` and the existing `admin` role. The additional participants `pugliens@gmail.com` and `frevadiscor@gmail.com` SHALL retain their existing shared-catalog subjects `user:moody` and `user:frevadiscor`, respectively, with role `rage-quit-user` and without administrator authorities. Authentication identity SHALL remain independent of application entitlements, which SHALL be granted by explicit client policy. Email and verified-email claims in tokens and OIDC UserInfo SHALL consistently describe the actual authenticated identity, not the administrator for every login.

#### Scenario: Google and password identify the same administrator
- **WHEN** the configured administrator authenticates once through Google and once through the local password method
- **THEN** both successful authorizations for clients permitting that authentication method SHALL contain the same canonical subject and `admin` role

#### Scenario: Application validates a token from either cluster
- **WHEN** an application receives an unexpired token issued through either edge
- **THEN** it SHALL validate issuer, signature, audience, expiry, and roles without knowing which cluster issued it

#### Scenario: Additional Google participant authenticates
- **WHEN** either additional verified Google participant authorizes Rage Quit
- **THEN** the token and OIDC identity SHALL identify that participant with their existing stable subject and verified email, the `rage-quit-user` role, and no admin role

### Requirement: Google authentication is optional to local availability
Both instances SHALL accept only explicitly configured, verified Google identities when Google authentication is used. The configured set SHALL include the existing administrator and the two Rage Quit participants, without introducing public signup. Both instances SHALL expose a password fallback for the configured administrator, backed only by an Argon2id or bcrypt hash from SOPS, so a Google outage does not prevent authentication to existing clients permitting the fallback. Additional participants SHALL NOT receive local-password accounts, and Rage Quit SHALL require Google-backed authentication for all participants, including the administrator.

#### Scenario: Home authenticates over LAN without Internet
- **WHEN** Home cannot reach Google and the administrator submits the correct local password over the LAN path for an existing client permitting the fallback
- **THEN** the server SHALL complete the requested OAuth/OIDC authorization with the canonical administrator subject and `admin` role

#### Scenario: Cloud authenticates while Home is unavailable
- **WHEN** the Home cluster or Home Internet path is unavailable and the administrator reaches an existing Cloud application
- **THEN** the Cloud instance SHALL independently complete Google or permitted password authentication and issue a valid token

#### Scenario: Unconfigured Google identity attempts login
- **WHEN** Google returns a verified identity outside the configured participant set
- **THEN** authorization SHALL be denied without issuing a code or token

#### Scenario: Google returns an unverified email
- **WHEN** Google returns an allowed email without affirmative verified-email evidence
- **THEN** authorization SHALL be denied without issuing a code or token

#### Scenario: Google-only user attempts password login
- **WHEN** a user's configured methods include `google` but not `password`, and local password login is enabled for another user
- **THEN** the server SHALL reject that user's password authentication and SHALL NOT treat password login as a fallback for the Google-only account

#### Scenario: Google-only user signs in through Google
- **WHEN** a configured Google-only user authenticates with a verified Google identity
- **THEN** the server SHALL authorize the user with the configured canonical subject and groups without requiring or creating a local password

#### Scenario: Password session requests Rage Quit
- **WHEN** the administrator's only authentication evidence is the local-password fallback and Rage Quit authorization is requested
- **THEN** the server SHALL require Google-backed reauthentication before issuing the authorization

## ADDED Requirements

### Requirement: Interactive authorization is restricted by participant and client
Both auth instances SHALL authorize interactive clients against the actual canonical participant and trusted authentication source, with entitlements defined per client rather than encoded in a person's subject. For this change, the additional participants SHALL be permitted for Rage Quit only and SHALL NOT receive codes or tokens for existing browser or machine clients. Existing browser clients SHALL retain administrator-only access by default. A future client MAY grant access through its own explicit policy without changing the participant's canonical subject. Restrictions SHALL apply when reusing an existing browser session and defensively before token issuance, not only when handling the original Google callback. Unknown or insufficient identity metadata SHALL fail closed without altering existing machine-client behavior.

#### Scenario: Participant reuses a Rage Quit session for another application
- **WHEN** either additional participant requests Grafana, Homepage, Home Assistant, Zigbee2MQTT, or another non-permitted client through an already authenticated session
- **THEN** no authorization code or token SHALL be issued for that client

#### Scenario: Shared session is restored on the other auth edge
- **WHEN** an additional participant's authenticated session is restored through shared authorization state
- **THEN** the stable identity, non-admin role, authentication provenance, and client restrictions SHALL still apply

#### Scenario: Older session lacks trusted participant metadata
- **WHEN** an old session cannot establish the canonical participant or the authentication source required by the requested client
- **THEN** the server SHALL require reauthentication or deny authorization rather than infer administrator or Google privileges

#### Scenario: Existing machine client authenticates
- **WHEN** the configured Grafana API client completes valid client-credentials authentication
- **THEN** its existing service subject, audience, scopes, and service-role contract SHALL remain unchanged
