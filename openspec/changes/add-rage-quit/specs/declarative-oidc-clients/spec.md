# Declarative OIDC clients delta

## ADDED Requirements

### Requirement: Declarative interactive client policies fail closed
Externally configured browser clients SHALL support explicit permitted canonical subjects and required authentication source. Existing browser clients without a new policy SHALL remain administrator-only and retain their supported authentication methods. Invalid policy definitions, unknown participant references, and unknown authentication sources SHALL fail startup validation. Client-credentials behavior SHALL remain independent of interactive participant policy.

#### Scenario: Rage Quit declares participant access
- **WHEN** Rage Quit is configured for the three canonical participants and Google-only authentication
- **THEN** authorization SHALL allow exactly those subjects with verified Google-backed authentication

#### Scenario: Existing client has no new participant policy
- **WHEN** an existing Grafana, Homepage, Home Assistant, or Zigbee2MQTT browser client is loaded without an explicit new subject policy
- **THEN** it SHALL remain administrator-only rather than admit every configured Google participant

#### Scenario: Policy contains an unknown subject or source
- **WHEN** a client policy references an unconfigured participant or unsupported authentication source
- **THEN** startup validation SHALL fail rather than ignore the restriction

### Requirement: Rage Quit registration preserves the shared issuer contract
The shared client catalog SHALL register `rage-quit` with audience `rage-quit`, scopes `openid`, `profile`, and `email`, only the authorization-code grant, required PKCE, `client_secret_basic`, and exact callback `https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit`. Its secret SHALL be resolved separately from encrypted configuration, with equivalent registration on both authorization-server edges. The application workload itself SHALL remain Cloud-only. Registration SHALL NOT grant refresh tokens, wildcard callbacks, machine grants, or scopes belonging to other applications.

#### Scenario: Valid Rage Quit authorization is exchanged
- **WHEN** an allowed Google-backed participant completes the registered callback using valid client authentication and PKCE
- **THEN** tokens SHALL use the Rage Quit audience and that participant's identity with only allowed scopes

#### Scenario: Callback or PKCE does not match
- **WHEN** a request supplies a non-registered redirect URI or an invalid/missing required PKCE proof
- **THEN** the authorization/token exchange SHALL be denied

#### Scenario: Shared catalog is loaded on either edge
- **WHEN** Cloud or Home loads the Rage Quit client configuration and resolved secret
- **THEN** both SHALL expose equivalent registration and participant policy without installing a Rage Quit application in Home
