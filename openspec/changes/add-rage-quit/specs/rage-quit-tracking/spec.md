# Rage Quit tracking

## Purpose

Define reliable timestamped cigarette and resistance records, owner corrections, and explicit zero-smoking declarations for the group's shared tracking period.

## ADDED Requirements

### Requirement: Each smoking or resistance occasion is a timestamped event
The system SHALL provide separate actions for one cigarette smoked and one occasion resisted. Every event SHALL have a unique identity, authenticated owner, type, and effective instant. Participants SHALL be able to record now or a past instant within the common tracking period. Future instants, unsupported types, and dates before the shared start SHALL be rejected. UTC instants SHALL be preserved, and day/date presentation SHALL use `Europe/Rome`.

#### Scenario: Participant records smoking now
- **WHEN** a participant submits the smoke-now action
- **THEN** exactly one smoking event is recorded at the server's current instant for that participant

#### Scenario: Participant backdates a resistance
- **WHEN** a participant submits a valid past resistance instant
- **THEN** one resistance event is recorded at that instant and contributes to that Rome day's resistance count

#### Scenario: Invalid or ambiguous date is submitted
- **WHEN** an instant is in the future, before the tracking start, or represented by an unresolved ambiguous/nonexistent Rome local time
- **THEN** the request is rejected without adding an event

### Requirement: Owner corrections update derived views consistently
Participants SHALL be able to change the type or instant and delete their own events. A successful mutation SHALL update persistence atomically and all subsequently requested counts, scores, intervals, and charts SHALL reflect the corrected event set. Stale conflicting corrections SHALL NOT silently overwrite newer changes.

#### Scenario: Cigarette is moved to another day
- **WHEN** the owner changes a cigarette's effective instant to a valid instant on another Rome date
- **THEN** the old and new daily counts and all affected intervals and totals reflect the move

#### Scenario: Resistance is corrected into a cigarette
- **WHEN** the owner changes a resistance event to a smoking event
- **THEN** the resistance point is removed and one cigarette penalty is added for the effective date

#### Scenario: Stale correction is submitted
- **WHEN** a correction targets a record version that has already changed
- **THEN** the request reports a conflict rather than overwriting the newer record

### Requirement: Repeated submissions cannot duplicate a successful event
The system SHALL support user-scoped deduplication of retried event submissions. Repeating the same successful submission SHALL return the same event without another count; reusing its submission identity for conflicting contents SHALL be rejected. Independent intentional submissions SHALL remain distinct events.

#### Scenario: Client retries after an uncertain response
- **WHEN** the same participant repeats the same event submission with the same deduplication identity
- **THEN** exactly one event exists and scoring counts it once

### Requirement: Zero-smoking declarations differ from missing reports
Participants SHALL be able to declare or withdraw zero smoking for today or a previous date in the tracking period. A declaration SHALL be rejected if that date contains a smoking event and SHALL NOT be allowed for future dates. Resistance events SHALL NOT prevent a zero declaration or automatically imply one. Any correction introducing a cigarette into a confirmed-zero date SHALL invalidate the declaration atomically. Removing the last cigarette SHALL NOT automatically declare zero.

#### Scenario: Participant confirms a resistance-only day
- **WHEN** a date contains resistances but no cigarettes and the owner confirms zero smoking
- **THEN** the date shows zero confirmed smoking and retains its resistance points

#### Scenario: Cigarette is added after zero confirmation
- **WHEN** a participant adds or moves a cigarette into a confirmed-zero date
- **THEN** the zero confirmation is invalidated in the same transaction as the cigarette mutation

#### Scenario: Current day is confirmed zero
- **WHEN** a participant confirms zero for today
- **THEN** the declaration is visible as of confirmation and the current day's reporting remains in progress

#### Scenario: Participant withdraws a zero declaration
- **WHEN** the owner explicitly withdraws a zero-smoking declaration for today or a previous tracking date
- **THEN** the declaration is removed, any resistance events remain intact, and the date returns to missing smoking-report status until a new smoking report is recorded

#### Scenario: No smoking report is available
- **WHEN** a date has neither smoking events nor a zero confirmation, including a resistance-only date
- **THEN** its smoking report is marked missing rather than confirmed zero

### Requirement: Records survive restarts and updates
Successful writes SHALL be transactional and durable across application restarts and compatible image updates. The common start date SHALL be shared by all participants, persisted once, and SHALL NOT silently change on restart. The system SHALL refuse usable readiness when its database is inaccessible, invalid, or incompatible rather than discard records or fall back to ephemeral storage.

#### Scenario: Application restarts after confirmed writes
- **WHEN** the application restarts with the same persistent storage
- **THEN** events, zero confirmations, and the common start date remain available

#### Scenario: Runtime configuration changes the persisted start date
- **WHEN** startup configuration supplies a different common start date from the initialized database
- **THEN** startup/readiness rejects the mismatch rather than changing the reporting period silently
