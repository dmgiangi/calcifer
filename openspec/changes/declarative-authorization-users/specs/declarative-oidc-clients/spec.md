# Spec Delta

## ADDED Requirements

### Requirement: Interactive client access can be restricted by configured groups
An interactive OIDC client SHALL be configurable with the groups permitted to authorize it. Authorization SHALL be denied unless the authenticated user's trusted catalog membership includes a permitted group. Existing interactive clients without an explicit group policy SHALL remain restricted to the existing `admin` group. Group policy SHALL NOT change client-credentials authorization.

#### Scenario: Client admits a configured group
- **WHEN** an interactive client permits `rage-quit` and a verified user is assigned that group
- **THEN** the user SHALL be eligible to authorize that client subject to its other registered OIDC requirements

#### Scenario: User has no permitted group
- **WHEN** a user requests an interactive client but none of the user's configured groups are permitted by that client
- **THEN** the Authorization Server SHALL deny authorization without issuing a code or token

#### Scenario: Existing client has no group policy
- **WHEN** an existing interactive client is loaded without an explicit group policy
- **THEN** only a user assigned to the existing `admin` group SHALL be eligible to authorize it

#### Scenario: Machine client uses client credentials
- **WHEN** a configured client completes valid client-credentials authentication
- **THEN** its existing service subject, audience, scopes, and service-role contract SHALL remain unchanged by interactive group policy
