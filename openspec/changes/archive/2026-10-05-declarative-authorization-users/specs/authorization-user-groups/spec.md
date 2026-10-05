# Spec Delta

## Purpose

Provides an explicit, reviewable catalog for human identities in the Authorization Server, including stable subjects, group membership, allowed login methods, and the claims derived from that trusted configuration.

## ADDED Requirements

### Requirement: Human users are declared with stable identity, groups, and login methods
The Authorization Server SHALL maintain a configuration-driven catalog of human users. Each entry SHALL identify a verified Google email, a unique stable canonical subject, one or more configured groups, and the interactive authentication methods permitted for that user. Unknown groups or methods, duplicate subjects, and invalid or ambiguous entries SHALL fail closed. Login SHALL NOT create or enroll users automatically.

#### Scenario: Configured Google user is mapped to the declared identity
- **WHEN** Google returns a verified email present in the user catalog
- **THEN** the Authorization Server SHALL resolve the configured canonical subject and groups for that user

#### Scenario: Unknown Google identity attempts sign-in
- **WHEN** Google returns a verified email absent from the user catalog
- **THEN** the Authorization Server SHALL deny sign-in without creating an account or issuing an authorization

#### Scenario: Invalid user catalog is loaded
- **WHEN** the catalog contains a duplicate subject, unknown group, or unsupported authentication method
- **THEN** configuration validation SHALL fail rather than start with partial or ambiguous user permissions

### Requirement: Group claims come only from the configured user catalog
The Authorization Server SHALL derive interactive-user group membership from the configured catalog, not from Google claims, request parameters, or user-controlled session data. OIDC tokens and UserInfo SHALL expose the configured memberships in a `groups` claim. The existing `roles` claim SHALL remain available with its current values for existing users, and SHALL NOT grant `admin` unless the trusted catalog assigns the `admin` group.

#### Scenario: User has a non-administrator group
- **WHEN** an authenticated user's catalog entry assigns `rage-quit` but not `admin`
- **THEN** OIDC tokens and UserInfo SHALL report the configured `rage-quit` group and SHALL NOT report administrator membership or role

#### Scenario: Provider response contains an unconfigured group
- **WHEN** a provider response or request supplies a group not assigned in the user catalog
- **THEN** the Authorization Server SHALL ignore that value and issue only the catalog-assigned groups

#### Scenario: Existing administrator authenticates
- **WHEN** the existing administrator authenticates through an allowed method
- **THEN** the canonical subject and `admin` role expected by existing clients SHALL remain unchanged
