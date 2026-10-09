## MODIFIED Requirements

### Requirement: Durable log storage
The system SHALL persist log data on the existing local ext4 PersistentVolumeClaim
attached to VictoriaLogs, managed through built-in retention partitioning
configured to six backend-native months (`retentionPeriod: 6M`). This shared policy
SHALL cover infrastructure logs and both external clients. Disk-pressure cleanup
SHALL be documented separately and SHALL NOT be represented as guaranteeing six
months of actual retained history.

#### Scenario: Log retention expiration
- **WHEN** log data partitions exceed the configured six-month time-retention period
- **THEN** VictoriaLogs SHALL prune the expired daily partitions from local storage

#### Scenario: Retention setting is increased
- **WHEN** time retention changes from 14 days to six months
- **THEN** existing retained data and its PVC SHALL be preserved and previously expired data SHALL not be claimed as recovered

#### Scenario: Disk cleanup limit is reached before six months
- **WHEN** storage pressure causes older partitions to be removed before their time-retention age
- **THEN** operational telemetry and documentation SHALL distinguish this early cleanup from age-based expiration
