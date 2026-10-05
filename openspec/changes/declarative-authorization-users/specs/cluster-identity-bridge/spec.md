# Spec Delta

## MODIFIED Requirements

### Requirement: Google authentication is optional to local availability
Both instances SHALL accept only explicitly configured, verified Google identities when Google authentication is used, mapping each identity to its configured canonical subject and group membership. Both instances SHALL expose a password fallback for the configured administrator, backed only by an Argon2id or bcrypt hash from SOPS, so a Google outage does not prevent authentication where password login is enabled. Local password authentication SHALL be accepted only for users whose configured authentication methods explicitly include `password`. A Google-only user SHALL NOT be authenticated by password, even when local password login is enabled for another user. Cloud and Home SHALL apply equivalent user and authentication-method configuration.

#### Scenario: Home authenticates over LAN without Internet
- **WHEN** Home cannot reach Google and the configured administrator, whose authentication methods include `password`, submits the correct local password over the LAN path
- **THEN** the server SHALL complete the requested OAuth/OIDC authorization with the administrator's canonical subject and existing `admin` role

#### Scenario: Cloud authenticates while Home is unavailable
- **WHEN** the Home cluster or Home Internet path is unavailable and the administrator reaches a Cloud application
- **THEN** the Cloud instance SHALL independently complete an allowed Google or password authentication and issue a valid token with the same canonical identity and groups as Home

#### Scenario: Unconfigured Google identity attempts login
- **WHEN** Google returns a verified identity absent from the configured user catalog
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
