# cluster-namespace-layout Specification

## Purpose

Define stable platform and application-domain namespace boundaries for both Calcifer clusters, including safe migration and cleanup behavior for namespaced workloads and their dependencies.

## Requirements

### Requirement: Clusters use platform and domain namespace boundaries
The system SHALL preserve dedicated namespaces for platform capabilities and SHALL place application resources in domain namespaces rather than one namespace per workload. `flux-system`, `kube-system`, `cert-manager`, `monitoring`, `authorization`, and `private-transit` SHALL remain separate platform namespaces on clusters where they are installed, and `lan-dns` SHALL remain a separate Home platform namespace.

#### Scenario: Home application placement is reconciled
- **WHEN** Flux reconciles the post-migration state on `calcifer-home`
- **THEN** Home Assistant, Mosquitto, Zigbee2MQTT, and cluster-hosted voice-assistant resources SHALL reside in `home-automation`
- **AND** Homepage SHALL reside in `web`

#### Scenario: Cloud web placement is reconciled
- **WHEN** Flux reconciles the post-migration state on `calcifer-cloud`
- **THEN** Homepage and the Cloud edge proxy resources for Home Assistant and Zigbee2MQTT SHALL reside in `web`

#### Scenario: Platform boundaries remain intact
- **WHEN** application namespaces are consolidated
- **THEN** platform workloads, credentials, and state SHALL remain in their existing platform namespaces and SHALL continue serving unrelated capabilities

### Requirement: Namespace-sensitive dependencies follow the destination workload
The system SHALL update every namespace-sensitive dependency when a resource moves, including Service DNS names, NetworkPolicy namespace selectors, namespaced Traefik references, certificate Secrets, SOPS-encrypted Secrets, backup jobs, Flux health checks, observability filters, and operational commands.

#### Scenario: Home automation services communicate after consolidation
- **WHEN** Home Assistant and Zigbee2MQTT connect to Mosquitto after cutover
- **THEN** they SHALL use the Mosquitto Service in `home-automation` with preserved authentication
- **AND** MQTT communication SHALL not depend on the removed `mqtt` namespace

#### Scenario: Ingress remains authorized
- **WHEN** Traefik in `kube-system` accesses a migrated HTTP workload
- **THEN** the destination NetworkPolicy SHALL permit only the required ingress path and the route SHALL resolve namespaced Service, Middleware, transport, and TLS Secret references in the destination namespace

#### Scenario: Namespace labels remain observable
- **WHEN** monitoring collects logs or metrics from a migrated workload
- **THEN** dashboards, alerts, and documented queries SHALL use the post-migration namespace labels where they filter by namespace

### Requirement: Stateful consolidation uses a guarded backup and restore cutover
The system SHALL migrate Home Assistant and Zigbee2MQTT into newly created PVCs by using final verified snapshots from their existing encrypted Restic repositories. Scheduled backups SHALL be suspended during cutover, stateful clients SHALL be stopped before their final snapshots, and the old namespaces and PVCs SHALL remain available until restored workloads pass validation.

#### Scenario: Final Home Assistant snapshot is accepted
- **WHEN** the final Home Assistant backup is created for migration
- **THEN** the snapshot SHALL contain configuration and `.storage` state
- **AND** its SQLite database SHALL pass an integrity check before that snapshot is selected for restore

#### Scenario: Final Zigbee2MQTT snapshot is accepted
- **WHEN** the final Zigbee2MQTT backup is created for migration
- **THEN** the snapshot SHALL contain configuration, coordinator and network state, paired-device state, and checksum metadata
- **AND** its recorded checksums SHALL pass before that snapshot is selected for restore

#### Scenario: Restore validation fails
- **WHEN** a restore job cannot retrieve, verify, or populate the selected snapshot into its replacement PVC
- **THEN** the new workload SHALL remain stopped
- **AND** the old namespace and PVC SHALL not be pruned

#### Scenario: Restored workloads start in dependency order
- **WHEN** replacement PVC validation succeeds
- **THEN** Mosquitto SHALL become ready before Zigbee2MQTT starts
- **AND** Zigbee2MQTT and required speech services SHALL become available before Home Assistant cutover is declared successful

### Requirement: Cutover and cleanup preserve a rollback boundary
The system SHALL stage replacement namespaces separately from route cutover and destructive cleanup. Canonical hostnames SHALL have only one active route per cluster during cutover, and obsolete namespaces SHALL be removed only after application, routing, authentication, backup, and observability validation succeeds.

#### Scenario: Replacement resources are staged
- **WHEN** the new domain namespaces and PVCs are first reconciled
- **THEN** they SHALL not create conflicting canonical ingress routes or start workloads against empty state before restore completes

#### Scenario: Cutover validation succeeds
- **WHEN** Home Assistant, Zigbee2MQTT, Mosquitto, voice services, Homepage, Cloud edge routing, and resumed backup jobs pass their declared checks
- **THEN** Flux MAY prune the obsolete workload namespaces and their retained PVCs in a later cleanup reconciliation

#### Scenario: Cutover validation fails
- **WHEN** any required functional or routing check fails before cleanup
- **THEN** operators SHALL be able to stop replacement workloads and restore the previous routes and workloads using the retained old namespaces and PVCs