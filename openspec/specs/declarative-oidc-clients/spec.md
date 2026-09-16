# Declarative OIDC clients

## Purpose

Define declarative loading of OAuth 2.1 and OIDC client definitions from external YAML configuration in the Authorization Server, separating client configuration from Java code and secrets.

## Requirements

### Requirement: External client definitions
The Authorization Server SHALL load OAuth/OIDC client definitions from an externally mounted Spring YAML configuration under `identity.clients`, in addition to supporting the existing configured clients during migration.

#### Scenario: Homepage client is declared in YAML
- **WHEN** a valid Homepage client definition is mounted before startup
- **THEN** the Authorization Server registers the client without a Java code change or image rebuild

#### Scenario: Configuration is reloaded through rollout
- **WHEN** the client ConfigMap or referenced Secret changes
- **THEN** the Authorization Server restarts through a controlled rollout and activates the new client definition

### Requirement: Secret separation
The system SHALL allow client secrets in externally supplied environment variables or Kubernetes Secrets referenced by placeholders, while keeping non-secret client metadata in ConfigMap YAML.

#### Scenario: Secret is resolved at startup
- **WHEN** a YAML client definition contains a secret placeholder and the corresponding environment variable is present
- **THEN** the resulting RegisteredClient uses the resolved secret

#### Scenario: Secret is missing
- **WHEN** a required client secret cannot be resolved
- **THEN** startup validation fails rather than registering a client with an empty or literal placeholder secret

### Requirement: Generic client registration
The Authorization Server SHALL translate each declarative client definition into a RegisteredClient including client ID, authentication method, grant types, redirect URIs, scopes, PKCE requirement, audience and token TTL where configured.

#### Scenario: Authorization-code client
- **WHEN** a client declares authorization-code grant, redirect URI, OIDC scopes and PKCE
- **THEN** the repository exposes an authorization-code client with those exact properties

#### Scenario: Client-credentials client
- **WHEN** a client declares client-credentials grant and no browser callback
- **THEN** the repository exposes a machine client without authorization-code behavior

### Requirement: Backward-compatible token claims
The Authorization Server SHALL preserve existing Grafana client behavior while resolving configurable token audience and client identity from the declarative definition when available.

#### Scenario: Existing Grafana browser client
- **WHEN** the Grafana browser client authenticates interactively
- **THEN** its subject, role and audience claims remain compatible with the current integration

#### Scenario: New Homepage client receives tokens
- **WHEN** Homepage completes an OIDC flow
- **THEN** issued tokens use the configured Homepage audience and the existing identity claims needed by OIDC user information
