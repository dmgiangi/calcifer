# Design

## Context

See proposal.md. ESP32 uses esp-idf-hal 0.47 and externally powered DS18B20s.
Host tests exercise a logical bus, not actual GPIO timing. Control is local.

## Goals / Non-Goals

**Goals:** reduce timing jitter and preserve evidence of intermittent failures.
**Non-Goals:** wiring diagnosis, parasite power, retries, changing safety limits,
sampling rate, persistent settings or replacing the bus with a new dependency.

## Decisions

- Extract timing operations behind a small GPIO interface, shared by firmware
  and host trace tests. Use HAL interrupt-free closures for reset through presence
  detection, write-low through release, and read-low through sample. Recovery and
  the 750 ms conversion wait remain outside critical sections. Read delays are
  3 + 9 microseconds, rather than 6 + 9, leaving GPIO overhead margin.
- Collect categorized events per cycle without changing existing successful
  readings or batch-wide GPIO-error behavior. Distinguish no presence, search
  conflict, ROM CRC, unsupported family, search bound, conversion timeout,
  scratchpad CRC, invalid scratchpad, range and GPIO errors.
- Accumulate saturating counters in RAM only. Retain total/failed cycles,
  duration, uptime, last-cycle and historical errors. Track per-ROM successes,
  failures, consecutive failures, detection, health and last success/error uptime.
  Missing tracked probes have a separate not-detected reason. Successful cycles
  clear current errors but preserve historical evidence.
- Add retained `therment/therment-01/probes/diagnostics`, schema version 1,
  published on each sample and republished on reconnect. HA diagnostic entities
  show failed cycles and each probe's failure count with detailed attributes.
  Include firmware version and uptime; counters reset at reboot. No command or
  secrets are included. Avoid NVS writes and unbounded histories.
- Keep existing temperature/status payloads and five-failure inhibition intact.
  Do not add retries that could mask the remaining electrical problems.

## Risks / Trade-offs

- Interrupt masking affects other work → hold only individual critical sequences
  (longest nominal hold 550 microseconds), never a transaction or conversion.
- Software delays still have platform overhead → host tests check sequencing;
  live telemetry and an electrical trace remain necessary for physical proof.
- Hardware faults remain possible → document external pull-up and powered probes.
- Reboot loses counters → explicitly boot-scope all counters and retain payloads
  at the broker; HA history can preserve trends across samples.

## Migration Plan

Run host regressions and OTA-profile compilation. After operator confirms loads
safe, publish a unique release via existing OTA tooling and verify the matching
installed version. Read only allowlisted diagnostic/state topics and observe
multiple samples. Existing rollback protection and settings remain unchanged.