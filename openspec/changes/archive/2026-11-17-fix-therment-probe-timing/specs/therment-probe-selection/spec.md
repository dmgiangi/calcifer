# Spec Delta

## ADDED Requirements

### Requirement: OneWire timing tolerates concurrent firmware work
The firmware SHALL protect timing-sensitive OneWire reset, write and read
sequences from ordinary task preemption and interrupts and SHALL leave timing
margin before the DS18B20 read deadline. Conversion waits and bus recovery
SHALL NOT disable interrupts. Sampling SHALL remain bounded without retries.

#### Scenario: Network activity occurs during sampling
- **WHEN** network work is active while probes are sampled
- **THEN** it SHALL NOT preempt a protected bit pulse or presence/read sample
- **AND** conversion waits SHALL allow normal scheduling

### Requirement: Probe diagnostics distinguish failure causes
The firmware SHALL publish retained, boot-scoped diagnostic data on
`therment/therment-01/probes/diagnostics` after sampling and on reconnection.
The payload SHALL include schema and firmware versions, uptime, cycle duration,
total and failed cycles, categorized event counts, current and historical errors,
and per-ROM success/failure counts, consecutive failures, detection, health and
last success/error uptime. Home Assistant SHALL discover diagnostic entities
for failed cycles and per-probe failure counts. Existing temperature/status
contracts and five-failure shutdown SHALL remain unchanged.

#### Scenario: A CRC error affects one probe
- **WHEN** one scratchpad fails CRC while another probe reads correctly
- **THEN** diagnostics SHALL identify the affected ROM and scratchpad CRC cause
- **AND** the other probe's successful reading SHALL remain usable

#### Scenario: Recovery preserves evidence
- **WHEN** a valid reading follows a failed cycle
- **THEN** current errors SHALL clear for recovered probes
- **AND** cumulative counts and historical error evidence SHALL remain

#### Scenario: Missing probe and GPIO failure
- **WHEN** a tracked probe is absent or a bus GPIO operation fails
- **THEN** diagnostics SHALL distinguish not-detected from a GPIO batch error
- **AND** each affected tracked probe SHALL record one failed sample

#### Scenario: Device restarts or reconnects
- **WHEN** the device restarts
- **THEN** counters SHALL restart in RAM without additional NVS writes
- **WHEN** MQTT reconnects
- **THEN** the current diagnostic snapshot SHALL replace retained old data