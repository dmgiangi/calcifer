# Spec Delta

## MODIFIED Requirements

### Requirement: Interactive client access can be restricted by configured groups
An interactive OIDC client SHALL be configurable with permitted catalog groups, canonical subjects, and a required authentication method. Where groups are configured, a user SHALL belong to at least one permitted group; where subjects are configured, the user's canonical subject SHALL be in the allowlist; and the trusted authentication method SHALL match when one is required. If neither a group nor subject restriction is configured, the existing administrator-only default SHALL apply. Unknown subject or method references SHALL fail startup validation. Interactive policy SHALL be enforced when authorization is requested and rechecked against trusted identity and authentication provenance before a code is exchanged or a token is issued. Missing or insufficient restored identity data SHALL fail closed. Client-credentials authorization SHALL remain independent of interactive participant policy.

#### Scenario: Client admits a configured group
- **WHEN** an authenticated user belongs to a group permitted by an interactive client and satisfies its other configured identity restrictions
- **THEN** the user SHALL be eligible to authorize that client subject to its registered OIDC requirements

#### Scenario: User has no permitted group
- **WHEN** a client configures permitted groups and the authenticated user's trusted catalog membership includes none of them
- **THEN** the Authorization Server SHALL deny authorization without issuing a code or token

#### Scenario: Client restricts canonical subjects
- **WHEN** an interactive client permits a configured canonical subject and the authenticated user has that subject
- **THEN** the user SHALL be eligible only for that client and only when any other configured group and authentication-method restrictions are also satisfied

#### Scenario: User subject or authentication method is not permitted
- **WHEN** an authenticated user has a subject outside the configured allowlist or authenticates through a method other than the one required by the client
- **THEN** the Authorization Server SHALL deny authorization and SHALL NOT issue a code or token

#### Scenario: Client requires a fresh authentication method
- **WHEN** an otherwise eligible user has a session authenticated by a method that does not satisfy the client's required method
- **THEN** the Authorization Server SHALL require the configured authentication method before issuing an authorization code

#### Scenario: Policy references an unknown subject or method
- **WHEN** client configuration references an unknown canonical subject or unsupported authentication method
- **THEN** startup validation SHALL fail rather than ignore the restriction

#### Scenario: Existing client has no group policy
- **WHEN** an existing interactive client is loaded without an explicit group or subject restriction
- **THEN** only a user assigned to the existing `admin` group SHALL be eligible to authorize it

#### Scenario: Restored session lacks trusted policy data
- **WHEN** a code is exchanged using a restored session whose identity or authentication provenance cannot satisfy the current client policy
- **THEN** the Authorization Server SHALL deny the exchange without issuing a token

#### Scenario: Machine client uses client credentials
- **WHEN** a configured client completes valid client-credentials authentication
- **THEN** its existing service subject, audience, scopes, and service-role contract SHALL remain unchanged by interactive client policy