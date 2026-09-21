# Spec Delta

## Purpose

Define a controlled migration capability for the personal Azure workloads, including the target subscription layout, protected backup cutover, validation, and rollback window needed to move services without losing recoverability.

## ADDED Requirements

### Requirement: Personal Azure workloads use the approved target topology

The personal workloads SHALL reside in the `sub-personal` subscription using `rg-calcifer` for Calcifer resources and `calcifer.tech`, and `rg-dmgiangi-public` for the `dmgiangi.dev` public DNS zone.

#### Scenario: Regional resources use Italy North
- **WHEN** a regional Calcifer resource is provisioned after migration
- **THEN** it SHALL be located in Italy North and use the approved descriptive name without an arbitrary numeric sequence suffix

#### Scenario: Global DNS zones use their designated Resource Groups
- **WHEN** the DNS zones are inspected after migration
- **THEN** `calcifer.tech` SHALL be in `rg-calcifer` and `dmgiangi.dev` SHALL be in `rg-dmgiangi-public`

#### Scenario: Storage access does not depend on the unused VNet
- **WHEN** the migrated Blob Storage firewall is evaluated
- **THEN** access SHALL be controlled by the approved `calcifer-cloud` public egress IP and valid Azure authorization, without requiring `vnet01` or a recreated VNet

### Requirement: Azure Blob data migration preserves backup recoverability

The migration SHALL copy all required source containers to the Italy North Storage account while preserving the source backup service until the final cutover synchronization completes.

#### Scenario: Initial synchronization runs without a backup outage
- **WHEN** the replacement Storage account is prepared
- **THEN** existing Home Assistant, Zigbee2MQTT, and Velero backup workflows SHALL remain available while the initial and incremental data synchronization runs

#### Scenario: Final synchronization is consistent
- **WHEN** the final cutover begins
- **THEN** backup schedules SHALL be paused, active backup jobs SHALL finish, and the final synchronization SHALL complete before consumers are pointed at the replacement account

#### Scenario: Old data remains available during rollback
- **WHEN** the new Storage account or dependent workloads fail acceptance checks
- **THEN** the source Storage data and configuration SHALL remain available for rollback during the agreed retention window

### Requirement: Migration completion requires functional acceptance

The migration SHALL be accepted only after data integrity, backup, restore, network restriction, DNS-01, and Speech checks pass.

#### Scenario: New backup succeeds after cutover
- **WHEN** backup schedules are re-enabled against the replacement account
- **THEN** a new Home Assistant backup, Zigbee2MQTT backup, and Velero backup SHALL complete and be visible in the replacement Storage location

#### Scenario: Representative restore succeeds
- **WHEN** an operator restores representative Home Assistant, Zigbee2MQTT, and observability backup data
- **THEN** the restored data SHALL be usable and Velero SHALL report a completed restore for the selected observability workloads

#### Scenario: External integrations remain functional
- **WHEN** the post-cutover smoke tests run
- **THEN** Blob access from the approved Cloud egress IP, DNS-01 certificate issuance, and Speech connectivity SHALL succeed while unauthorized Blob access remains denied
