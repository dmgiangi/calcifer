# Rage Quit insights

## Purpose

Define transparent daily scores, elapsed-time statistics, cumulative group ranking, and charts that distinguish self-reported progress from missing data.

## ADDED Requirements

### Requirement: Daily penalties and resistance points remain separate
The system SHALL assign one penalty point per smoking event and one resistance point per resistance event on its `Europe/Rome` date. Resistance points SHALL NOT subtract penalties or alter smoking rank. Missing smoking reports SHALL remain distinct from confirmed-zero scores, and current-day figures SHALL be labelled provisional.

#### Scenario: Participant records two cigarettes and three resistances
- **WHEN** a Rome date contains two smoking events and three resistance events
- **THEN** its scores show two penalty points and three resistance points, not a net score of minus one

#### Scenario: Calendar day includes a clock change
- **WHEN** events span a Rome daylight-saving transition
- **THEN** daily scores follow actual Rome date boundaries rather than assumed fixed 24-hour windows

### Requirement: General ranking uses the same cumulative smoking period
The general leaderboard SHALL compare recorded cigarette totals from the group's common start date through now, sorted ascending. Equal totals SHALL share a rank; display ordering SHALL NOT turn a tie into different ranks. Resistance totals SHALL be displayed separately without breaking ties. A participant with neither a smoking event nor any zero declaration SHALL be unranked, even with resistance events. Missing past smoking-report dates SHALL be counted and disclosed, and incomplete totals SHALL be marked provisional.

#### Scenario: Participants have different totals
- **WHEN** ranked participants have recorded four, seven, and nine cigarettes in the common period
- **THEN** their leaderboard order is four, seven, then nine

#### Scenario: Equal cigarette counts have different resistance points
- **WHEN** two participants have equal cigarette totals but different resistance totals
- **THEN** they share the same smoking rank and both resistance totals remain visible

#### Scenario: Participant has recorded only resistances
- **WHEN** a participant has resistance events but no cigarette events or zero declarations
- **THEN** that participant is shown as lacking a smoking rank rather than winning with apparent zero smoking

#### Scenario: Participant omitted a past day
- **WHEN** a participant has an established smoking report but one past date has neither cigarettes nor a zero declaration
- **THEN** their observed total is shown with a missing-day indicator and provisional status

### Requirement: Smoking intervals use adjacent cigarettes rather than resistance events
The personal dashboard SHALL show elapsed time since the last recorded cigarette, mean completed gap, and maximum completed gap. Gaps SHALL use adjacent smoking instants ordered chronologically, cross calendar boundaries, and exclude resistance events. The current open interval SHALL NOT be included in completed-gap aggregates. No prior cigarette SHALL result in an unavailable since-last value; fewer than two cigarettes SHALL result in unavailable mean/maximum, not invented gaps.

#### Scenario: Resistance occurs between two cigarettes
- **WHEN** cigarettes are recorded at 23:00 and 09:00 on the next day at an unchanged UTC offset, with a resistance between them
- **THEN** the completed smoking interval is ten hours and the resistance does not reset it

#### Scenario: First cigarette is recorded
- **WHEN** the participant has exactly one cigarette in the tracking period
- **THEN** time since that cigarette is available while mean and maximum completed gaps are unavailable

#### Scenario: History is corrected out of order
- **WHEN** an earlier cigarette is inserted, moved, or deleted
- **THEN** completed gaps and their aggregates are recomputed chronologically without negative intervals

### Requirement: Charts show days and group progress accessibly
The admitted dashboard SHALL provide daily cigarette/resistance bars, completed smoking-gap progression, cumulative cigarette comparisons for the three participants, and cumulative resistance progress. The default period SHALL span the shared start through today. Labels and explanatory text SHALL be in Italian, usable on mobile, and accompanied by textual values that do not depend only on color. Missing smoking reports and provisional values SHALL remain distinguishable from confirmed zero in daily and cumulative presentations.

#### Scenario: Resistance exists without a smoking declaration
- **WHEN** a day contains one resistance but has no smoking report
- **THEN** the chart shows the resistance and explicitly missing smoking data rather than a confirmed-zero smoking bar

#### Scenario: Correction changes a charted day
- **WHEN** a participant corrects an event and reloads insights
- **THEN** daily and cumulative charts reflect the same corrected counts as the scores and ranking

### Requirement: Resistance percentage describes recorded episodes only
Any displayed resistance percentage SHALL equal resistance events divided by all smoking plus resistance events within the displayed period. With no registered episodes it SHALL be unavailable. The interface SHALL describe it as a percentage of recorded episodes, not proof of abstinence or a clinical success rate.

#### Scenario: Participant records three resistances and one cigarette
- **WHEN** the displayed period contains three resistances and one cigarette
- **THEN** the recorded-episode resistance percentage is 75 percent

#### Scenario: No episodes exist
- **WHEN** the displayed period contains no smoking or resistance events
- **THEN** the resistance percentage is unavailable rather than zero or 100 percent
