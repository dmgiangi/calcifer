## MODIFIED Requirements

### Requirement: Enabled Grafana instances use the canonical authorization hostname
Every exposed Grafana instance SHALL validate the canonical issuer `https://auth.calcifer.tech` and use authorization code with PKCE. Its authorization, token, and user-info URLs SHALL all use `auth.calcifer.tech`, regardless of whether Grafana is deployed in Cloud or Home. The `auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` OAuth endpoint profiles SHALL not be used. A user carrying the canonical `admin` role SHALL receive Grafana organization Admin access.

#### Scenario: Administrator signs in through the canonical edge
- **WHEN** a user signs in to an enabled Grafana instance
- **THEN** browser authorization and Grafana token exchange SHALL both use `auth.calcifer.tech`, with split-horizon DNS selecting the appropriate reachable authorization-server instance

#### Scenario: Retired endpoint profile is configured
- **WHEN** a Grafana configuration contains `auth-cloud.calcifer.tech` or `auth-home.calcifer.tech` as an authorization, token, or user-info endpoint
- **THEN** the configuration SHALL be rejected during validation and SHALL not be deployed
