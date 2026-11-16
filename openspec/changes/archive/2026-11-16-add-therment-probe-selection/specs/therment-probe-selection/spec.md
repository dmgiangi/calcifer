# Therment probe selection

## ADDED Requirements

### Requirement: Probes are read independently
The firmware SHALL discover DS18B20 probes by CRC-validated ROM ID with bounded
bus work and SHALL read each scratchpad using Match ROM after conversion.

#### Scenario: Two probes share the bus
- **WHEN** chamber and freezer probes are connected
- **THEN** their readings SHALL remain distinct and independently monitored
- **AND** a valid sample SHALL reset that probe's consecutive-failure count

#### Scenario: A probe has transient read failures
- **WHEN** a previously valid probe fails one to four consecutive samples
- **THEN** its last valid temperature SHALL remain available and be marked stale
- **WHEN** the fifth consecutive sample fails
- **THEN** its temperature SHALL become unavailable and its health SHALL be fault
- **AND** both outputs SHALL be inhibited if it is the selected control probe
- **WHEN** any later sample from that probe is valid
- **THEN** its health SHALL return to ok and normal control SHALL resume

#### Scenario: A probe is missing or the bus reports an I/O error
- **WHEN** a tracked probe is not discovered or a sample cycle fails at the bus
- **THEN** the missing sample SHALL count as one consecutive failure for that probe

### Requirement: The control probe is explicitly configured
The firmware SHALL expose a Home Assistant MQTT select entity accepting `None`
or a detected valid ROM through non-retained commands. It SHALL persist the
association locally before reporting it accepted and preserve it across boot
and OTA. Autonomous control SHALL NOT depend on network connectivity.

#### Scenario: Operator changes the association
- **WHEN** control is off, both outputs are inactive and a valid ID is chosen
- **THEN** the firmware SHALL save the association and publish its actual state
- **AND** it SHALL require a fresh reading and a separate enable command
- **AND** it SHALL restart the compressor's five-minute guard

#### Scenario: Unsafe or invalid selection
- **WHEN** a retained command replay, unknown/malformed ID or active-control change arrives
- **THEN** the firmware SHALL NOT change the association

### Requirement: Probe failure cannot change the control source
Only the selected probe SHALL supply temperature to heating and cooling control.
Without a stored valid selection the firmware SHALL boot disabled. A selected
probe's last good reading SHALL remain usable for the first four consecutive
failures; at failure five both outputs SHALL be inhibited without selecting a
fallback until a valid sample returns.

#### Scenario: Selected probe remains absent
- **WHEN** the chamber probe is absent but the freezer probe remains healthy for
  five consecutive sampling cycles
- **THEN** both outputs SHALL be off after the fifth failed sample
- **AND** freezer monitoring SHALL remain independent
- **AND** the saved association SHALL remain unchanged

### Requirement: Home Assistant reports current probe availability
Each probe SHALL have stable per-ROM temperature and diagnostic-status
identities. A last-known value SHALL be marked stale for the first four failed
samples and unavailable on the fifth; status SHALL distinguish `ok`, `stale` and
`fault`.

#### Scenario: Probe disappears across reboot
- **WHEN** a previously discovered probe is missing after reconnection
- **THEN** its retained discovery SHALL NOT make its last reading appear valid

### Requirement: Operating cutouts do not invalidate measurements
Valid DS18B20 readings SHALL NOT be clamped to the desired operating envelope.
Below -15 °C the firmware SHALL inhibit cooling; above +40 °C it SHALL inhibit
heating. The opposite direction SHALL remain eligible to move the temperature
toward the setpoint.

#### Scenario: Temperature falls below the cooling cutout
- **WHEN** a valid reading is below -15 °C
- **THEN** the reading SHALL remain unchanged and cooling SHALL be off
- **AND** heating SHALL remain eligible according to the configured setpoint

#### Scenario: Temperature rises above the heating cutout
- **WHEN** a valid reading is above +40 °C
- **THEN** the reading SHALL remain unchanged and heating SHALL be off
- **AND** cooling SHALL remain eligible according to the configured setpoint