## MODIFIED Requirements

### Requirement: Tokens are interoperable and scoped
Cloud and Home SHALL use equivalent registered clients, audiences, scopes, roles, short access-token lifetimes, and signing material required for interoperability. Issued JWTs SHALL contain issuer, subject, expiry, audience, scopes, and only the configured authorization claims. They SHALL not contain cluster identity. The authorization server SHALL expose only authorization-code and client-credentials grants and SHALL NOT issue refresh tokens in this version.

#### Scenario: Client credentials work against either edge
- **WHEN** a configured private client requests an allowed scope through either Cloud or Home
- **THEN** it SHALL receive a short-lived JWT accepted by the configured resource servers

#### Scenario: Unauthorized scope is requested
- **WHEN** a client requests a scope not registered for it
- **THEN** both instances SHALL deny the request without issuing a JWT

#### Scenario: Client requests a refresh token
- **WHEN** a client requests the refresh-token grant or the `offline_access` scope
- **THEN** the authorization server SHALL deny the request without issuing a refresh token

### Requirement: OAuth state locality is explicit
The system SHALL use shared generation-scoped Redis authorization and browser-session state while Cloud and Home are connected. During Home isolation, new Home flows SHALL remain local to the active isolation epoch. No flow SHALL be promised seamless continuation across an endpoint hostname, store owner, isolation epoch, or Redis generation change.

#### Scenario: Normal Home application login while connected
- **WHEN** a user starts and completes a Home application's OAuth flow through the Home endpoint profile while Redis is available
- **THEN** Home SHALL handle the flow using the active shared Redis generation

#### Scenario: Home application login while isolated
- **WHEN** a user starts a Home application's OAuth flow after Home has entered isolation
- **THEN** Home SHALL handle the complete flow only through the active local isolation epoch

#### Scenario: Path or state generation changes during an active flow
- **WHEN** an active OAuth flow changes endpoint profile, store owner, isolation epoch, or Redis generation
- **THEN** the flow SHALL require restarting authentication rather than searching unsynchronized or obsolete state