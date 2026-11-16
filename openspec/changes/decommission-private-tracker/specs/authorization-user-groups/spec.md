# Spec Delta

## MODIFIED Requirements

### Requirement: Group claims come only from the configured user catalog
The Authorization Server SHALL derive interactive-user group membership from the configured catalog, not from Google claims, request parameters, or user-controlled session data. OIDC tokens and UserInfo SHALL expose the configured memberships in a `groups` claim. The existing `roles` claim SHALL remain available with its current values for existing users, and SHALL NOT grant `admin` unless the trusted catalog assigns the `admin` group.

#### Scenario: User has a non-administrator group
- **WHEN** an authenticated user's catalog entry assigns a non-administrator group but not `admin`
- **THEN** OIDC tokens and UserInfo SHALL report the configured group and SHALL NOT report administrator membership or role

#### Scenario: Provider response contains an unconfigured group
- **WHEN** a provider response or request supplies a group not assigned in the user catalog
- **THEN** the Authorization Server SHALL ignore that value and issue only the catalog-assigned groups

#### Scenario: Existing administrator authenticates
- **WHEN** the existing administrator authenticates through an allowed method
- **THEN** the canonical subject and `admin` role expected by existing clients SHALL remain unchanged