# Rage Quit access

## Purpose

Define private access for the three Rage Quit participants through the existing Google-federated issuer, while protecting personal records and unrelated applications.

## ADDED Requirements

### Requirement: Only the three configured Google participants are admitted
Rage Quit SHALL admit exactly `dem.gianluigi@gmail.com`, `pugliens@gmail.com`, and `frevadiscor@gmail.com` with verified Google identities through `https://auth.calcifer.tech`, mapped respectively to the stable subjects `user:admin`, `user:moody`, and `user:frevadiscor`. Those subjects identify people independently of client entitlements; this requirement SHALL NOT grant them access to other clients. The application SHALL validate the issuer's signature, expiry, client audience, login state/nonce, verified email, and expected stable subject. It SHALL NOT support public signup, direct Google credentials, local-password-only admission, or machine-token admission.

#### Scenario: Configured participant completes Google login
- **WHEN** any of the three configured participants completes the valid Google-backed OIDC flow
- **THEN** Rage Quit establishes a session for that participant's stable identity

#### Scenario: Invalid identity attempts admission
- **WHEN** the returned identity has an unknown subject/email, unverified email, invalid signature, wrong issuer/audience, expired token, or mismatched login state/nonce
- **THEN** Rage Quit denies admission without exposing tracking data

#### Scenario: Administrator has only a password-backed auth session
- **WHEN** the administrator starts Rage Quit login with only local-password authentication
- **THEN** a verified Google authentication step is required before Rage Quit admission

### Requirement: Sessions and mutations are protected
The application SHALL keep authentication in server-side sessions with Secure, HttpOnly, SameSite=Lax cookies. Tracking pages and APIs SHALL require admission; all state-changing operations, including logout, SHALL enforce CSRF protection and SHALL NOT be performed through GET requests. Logout SHALL invalidate the application session. Tokens and client secrets SHALL NOT be exposed in browser storage or application URLs.

#### Scenario: Anonymous visitor requests tracking data
- **WHEN** an unauthenticated visitor opens a protected page or calls a tracking API
- **THEN** the page automatically starts the Rage Quit OIDC flow and shows the authorization server's login panel, preserving state, nonce and S256 PKCE, or the API returns HTTP 401 without redirects or group or personal data

#### Scenario: Mutation lacks a valid CSRF token
- **WHEN** an authenticated session submits a mutation without valid CSRF protection
- **THEN** the operation is rejected and persisted data remains unchanged

#### Scenario: Participant logs out
- **WHEN** a participant submits a valid logout request
- **THEN** the former session can no longer read protected data or mutate records

### Requirement: Participants control only their own detailed records
All admitted participants SHALL be able to view group aggregates and comparison charts. Detailed event history and creation, correction, deletion, and zero-confirmation operations SHALL be restricted to the authenticated owner, including for the administrator. The server SHALL derive ownership from the session rather than trusting a submitted user identifier.

#### Scenario: Participant supplies another owner's event ID
- **WHEN** a participant attempts to read, modify, or delete another participant's detailed event
- **THEN** access is denied without modifying the record or exposing its private details

#### Scenario: Participant submits a forged owner
- **WHEN** a create or zero-confirmation request supplies another participant's identity
- **THEN** the server rejects the forged ownership or creates the record only for the authenticated participant

### Requirement: Tracking information is private operational data
The application SHALL NOT publish records anonymously or include tokens, client secrets, participant emails/subjects, or event payloads in routine logs or metric labels. Only public/static assets and non-sensitive health endpoints SHALL be accessible without an application session.

#### Scenario: Tracking mutation is observed operationally
- **WHEN** a participant records or corrects an event
- **THEN** routine diagnostics can report the operation outcome without exposing identity, authentication material, or smoking/resistance details
