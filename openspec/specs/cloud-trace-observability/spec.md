# Cloud trace observability

## Purpose

Define trace collection, querying, and durable storage for `calcifer-cloud`.

## Requirements

### Requirement: Shared six-month trace retention
The system SHALL configure the existing VictoriaTraces backend with six
backend-native months of time retention (`retentionPeriod: 6M`) on its existing
local ext4 PersistentVolumeClaim. This policy SHALL cover accepted infrastructure
traces and retained traces from both external clients. The existing 365-day
VictoriaMetrics retention SHALL remain unchanged. Disk-pressure cleanup SHALL be
documented separately from time retention.

#### Scenario: Trace retention expires
- **WHEN** stored trace partitions exceed the configured six-month time-retention period
- **THEN** VictoriaTraces SHALL remove expired partitions through its native retention mechanism.

#### Scenario: Existing trace data is retained during the update
- **WHEN** time retention changes from seven days to six months
- **THEN** the backend SHALL preserve its existing PVC and retained trace data, without claiming recovery of previously deleted data.

#### Scenario: Trace volume reaches its cleanup limit
- **WHEN** disk-pressure cleanup removes trace data earlier than six months
- **THEN** the system SHALL expose storage pressure and document that configured time retention is not a guaranteed minimum history window.