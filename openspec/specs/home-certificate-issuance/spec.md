# Home certificate issuance

## Purpose

Define Flux-managed Home cert-manager installation and canonical-name TLS
certificate issuance through Azure DNS DNS-01 validation.

## Requirements

### Requirement: Home cert-manager and DNS-01 issuers are Flux-managed
The system SHALL install cert-manager on `calcifer-home` through Flux before
applying Home certificate configuration. It SHALL provide separate Let's
Encrypt staging and production `ClusterIssuer` resources that use Azure DNS
DNS-01 validation for `calcifer.tech`, and it SHALL keep DNS credentials
SOPS-encrypted in Git.

#### Scenario: Home certificate configuration follows controller installation
- **WHEN** Flux reconciles the Home certificate-management roots on a new
  cluster
- **THEN** it SHALL report the cert-manager installation ready before it
  reconciles the Home issuers and encrypted DNS credential

#### Scenario: Home production issuer is usable
- **WHEN** a Home `Certificate` references the production Home ClusterIssuer
  for a `calcifer.tech` hostname
- **THEN** cert-manager SHALL complete DNS-01 validation without requiring an
  Internet-reachable Home ingress endpoint

### Requirement: Home services receive canonical-name certificates
The system SHALL issue an explicit Home certificate for every selected
Home-hosted canonical service hostname. A certificate SHALL remain valid when
the public DNS record for that hostname resolves to Cloud and LAN DNS resolves
it directly to the static Home address.

#### Scenario: Local direct HTTPS uses the canonical name
- **WHEN** an authorized LAN client resolves a selected service hostname to
  `192.168.0.102` and initiates HTTPS
- **THEN** Home Traefik SHALL present a valid certificate whose DNS name matches
  that hostname

### Requirement: Home Flux can decrypt Home secrets with the repository key
The system SHALL provision the repository's existing SOPS age private key as
the `sops-age` Secret in the Home `flux-system` namespace outside Git before a
Home Flux Kustomization decrypts a SOPS-encrypted Home Secret.

#### Scenario: Flux decrypts an encrypted Home DNS credential
- **WHEN** Flux reconciles the encrypted Home Azure DNS credential
- **THEN** the Kustomization SHALL decrypt and apply it without plaintext key
  material being stored in Git or emitted by the reconciliation configuration
