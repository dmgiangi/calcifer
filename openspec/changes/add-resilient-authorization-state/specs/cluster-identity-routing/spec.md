## MODIFIED Requirements

### Requirement: Canonical hostname is the only stateful OAuth profile
The system SHALL ensure that public DNS resolves `auth.calcifer.tech` to the Cloud edge, while Home split-horizon DNS SHALL resolve it to the Home edge. `auth.calcifer.tech` SHALL remain the sole token issuer, stateful OAuth hostname, and authorized Google callback. The `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` hostnames SHALL not be advertised or used as OAuth endpoint profiles.

#### Scenario: Issuer and OAuth endpoints use the canonical hostname
- **WHEN** an application validates a token issued through either endpoint
- **THEN** it SHALL use and accept `https://auth.calcifer.tech` for issuer, authorization, token, user-info, and callback URLs

#### Scenario: LAN user starts Google login on the canonical hostname
- **WHEN** a LAN browser initiates Google login through `auth.calcifer.tech` and Internet access remains available
- **THEN** Google SHALL return to `https://auth.calcifer.tech/login/oauth2/code/google` and split-horizon DNS SHALL route the callback to Home

#### Scenario: User starts Google login through either network location
- **WHEN** a browser initiates Google login through the canonical hostname from Cloud or Home
- **THEN** Google SHALL return to `https://auth.calcifer.tech/login/oauth2/code/google`, with split-horizon DNS selecting the reachable local edge

### Requirement: Identity routing does not depend on the private transit tunnel
Cloud and Home identity endpoints SHALL remain independently reachable through their local ingress paths. Home MAY use the private transit tunnel for connected Redis state, but loss of that dependency SHALL automatically move Home to isolated local state after failure hysteresis; Cloud SHALL continue through Redis when available. During generation recovery, stateful endpoints MAY return temporary unavailability and SHALL resume without operator action.

#### Scenario: Home tunnel is down
- **WHEN** the Cloud/Home private transit tunnel becomes unavailable long enough to cross the failure threshold
- **THEN** Cloud public authentication SHALL remain available through Redis and Home LAN password authentication SHALL resume through fresh isolated state

#### Scenario: Home tunnel returns
- **WHEN** private transit and Redis remain healthy for the configured recovery window
- **THEN** automatic generation recovery SHALL complete and the canonical endpoint SHALL resume connected operation after affected users restart authentication

#### Scenario: Recovery is in progress
- **WHEN** the expiring recovery gate is active
- **THEN** stateful identity endpoints SHALL return temporary unavailability rather than writing authorization state during the generation transition