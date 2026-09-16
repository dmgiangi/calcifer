# home-assistant-oidc-auth Specification

## Purpose
TBD - created by archiving change add-home-assistant-home-edge. Update Purpose after archive.
## Requirements
### Requirement: Declarative OIDC client registration for Home Assistant
The system SHALL configure an OIDC client definition for Home Assistant in `authorization-server` declaring authorization code grant, PKCE enforcement, OIDC standard scopes, and canonical redirect URI.

#### Scenario: Authorization Server registers Home Assistant client
- **WHEN** `authorization-server` starts with the Home Assistant client configuration
- **THEN** it SHALL register a client with ID `home-assistant`, redirect URI `https://home.calcifer.tech/auth/oidc/callback`, scopes `openid`, `profile`, `email`, and PKCE required.

#### Scenario: User initiates SSO login flow
- **WHEN** an unauthenticated user selects SSO on `https://home.calcifer.tech`
- **THEN** the client SHALL be redirected to `https://auth.calcifer.tech/oauth2/authorize` with valid client ID, redirect URI, and PKCE challenge.

### Requirement: Multi-provider authentication with break-glass recovery
Home Assistant SHALL support OpenID Connect authentication alongside native credentials, ensuring single sign-on for daily operations while retaining emergency local administrative access.

#### Scenario: Successful OIDC authentication
- **WHEN** an authorized user completes login at `https://auth.calcifer.tech` and returns to `/auth/oidc/callback`
- **THEN** Home Assistant SHALL validate the ID and access tokens, provision or link the user profile, and grant authorized session access.

#### Scenario: Identity Provider unreachable break-glass recovery
- **WHEN** `authorization-server` or WAN connectivity is down
- **THEN** an administrator on the local network SHALL be able to authenticate directly using native Home Assistant credentials.

