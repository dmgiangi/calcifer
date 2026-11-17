//! Bounded, boot-scoped evidence. Counters saturate and never write to NVS.

use super::{MonitoredProbe, ProbeHealth, Rom};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ReadFailure {
    NotDetected,
    NoPresence,
    SearchConflict,
    RomCrc,
    UnsupportedFamily,
    SearchLimit,
    ConversionTimeout,
    ScratchpadCrc,
    InvalidScratchpad,
    OutOfRange,
    Gpio,
}

impl ReadFailure {
    pub const ALL: [Self; 11] = [
        Self::NotDetected,
        Self::NoPresence,
        Self::SearchConflict,
        Self::RomCrc,
        Self::UnsupportedFamily,
        Self::SearchLimit,
        Self::ConversionTimeout,
        Self::ScratchpadCrc,
        Self::InvalidScratchpad,
        Self::OutOfRange,
        Self::Gpio,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::NotDetected => "not_detected",
            Self::NoPresence => "no_presence",
            Self::SearchConflict => "search_conflict",
            Self::RomCrc => "rom_crc",
            Self::UnsupportedFamily => "unsupported_family",
            Self::SearchLimit => "search_limit",
            Self::ConversionTimeout => "conversion_timeout",
            Self::ScratchpadCrc => "scratchpad_crc",
            Self::InvalidScratchpad => "invalid_scratchpad",
            Self::OutOfRange => "out_of_range",
            Self::Gpio => "gpio",
        }
    }
}

#[derive(Default)]
pub struct SampleDiagnostics {
    pub events: [u64; 11],
    pub probe_errors: Vec<(Rom, ReadFailure)>,
    pub batch_error: Option<ReadFailure>,
    pub last_error: Option<ReadFailure>,
}

impl SampleDiagnostics {
    pub fn record(&mut self, reason: ReadFailure) {
        let counter = &mut self.events[reason as usize];
        *counter = counter.saturating_add(1);
        self.last_error = Some(reason);
    }

    pub fn fail_batch(&mut self, reason: ReadFailure) {
        self.record(reason);
        self.batch_error = Some(reason);
    }

    pub fn fail_probe(&mut self, rom: Rom, reason: ReadFailure) {
        self.record(reason);
        self.probe_errors.push((rom, reason));
    }

    fn error_for(&self, rom: Rom) -> ReadFailure {
        if self.batch_error == Some(ReadFailure::Gpio) {
            return ReadFailure::Gpio;
        }
        self.probe_errors
            .iter()
            .find(|(id, _)| *id == rom)
            .map(|(_, reason)| *reason)
            .or(self.batch_error)
            .unwrap_or(ReadFailure::NotDetected)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProbeDiagnostics {
    pub rom: Rom,
    pub successes: u64,
    pub failures: u64,
    pub current_error: Option<ReadFailure>,
    pub last_error: Option<ReadFailure>,
    pub last_error_uptime_s: Option<u64>,
    pub last_success_uptime_s: Option<u64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Diagnostics {
    pub cycles: u64,
    pub failed_cycles: u64,
    pub events: [u64; 11],
    pub uptime_s: u64,
    pub cycle_duration_ms: u64,
    pub current_error: Option<ReadFailure>,
    pub last_error: Option<ReadFailure>,
    pub last_error_uptime_s: Option<u64>,
    pub probes: Vec<ProbeDiagnostics>,
}

impl Diagnostics {
    pub fn update(
        &mut self,
        cycle: &SampleDiagnostics,
        readings: &[MonitoredProbe],
        uptime_s: u64,
        duration_ms: u64,
    ) {
        self.cycles = self.cycles.saturating_add(1);
        self.uptime_s = uptime_s;
        self.cycle_duration_ms = duration_ms;
        self.current_error = cycle.last_error;
        for (total, count) in self.events.iter_mut().zip(cycle.events) {
            *total = total.saturating_add(count);
        }
        // Follow the monitor's bounded inventory, including missing saved probes.
        self.probes
            .retain(|probe| readings.iter().any(|r| r.rom == probe.rom));
        for reading in readings {
            let index = self
                .probes
                .iter()
                .position(|p| p.rom == reading.rom)
                .unwrap_or_else(|| {
                    self.probes.push(ProbeDiagnostics {
                        rom: reading.rom,
                        successes: 0,
                        failures: 0,
                        current_error: None,
                        last_error: None,
                        last_error_uptime_s: None,
                        last_success_uptime_s: None,
                    });
                    self.probes.len() - 1
                });
            let probe = &mut self.probes[index];
            if reading.health == ProbeHealth::Ok {
                probe.successes = probe.successes.saturating_add(1);
                probe.current_error = None;
                probe.last_success_uptime_s = Some(uptime_s);
            } else {
                let reason = cycle.error_for(reading.rom);
                probe.failures = probe.failures.saturating_add(1);
                probe.current_error = Some(reason);
                probe.last_error = Some(reason);
                probe.last_error_uptime_s = Some(uptime_s);
                if reason == ReadFailure::NotDetected {
                    let counter = &mut self.events[reason as usize];
                    *counter = counter.saturating_add(1);
                }
                self.current_error = self.current_error.or(Some(reason));
            }
        }
        if let Some(reason) = self.current_error {
            self.failed_cycles = self.failed_cycles.saturating_add(1);
            self.last_error = Some(reason);
            self.last_error_uptime_s = Some(uptime_s);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::probes::{Probe, ProbeMonitor};

    #[test]
    fn recovery_preserves_history_and_absence_is_distinct_from_gpio() {
        let rom = Rom([0x28, 1, 0, 0, 0, 0, 0, 0]);
        let mut monitor = ProbeMonitor::new(Some(rom));
        let mut diagnostics = Diagnostics::default();
        monitor.update(&[Probe {
            rom,
            temperature: Some(20.0),
        }]);
        diagnostics.update(&SampleDiagnostics::default(), monitor.readings(), 1, 780);
        monitor.update(&[]);
        diagnostics.update(&SampleDiagnostics::default(), monitor.readings(), 16, 780);
        assert_eq!(
            diagnostics.probes[0].current_error,
            Some(ReadFailure::NotDetected)
        );
        assert_eq!(diagnostics.events[ReadFailure::NotDetected as usize], 1);
        let mut cycle = SampleDiagnostics::default();
        cycle.fail_batch(ReadFailure::Gpio);
        monitor.update(&[]);
        diagnostics.update(&cycle, monitor.readings(), 31, 1);
        assert_eq!(diagnostics.probes[0].current_error, Some(ReadFailure::Gpio));
        monitor.update(&[Probe {
            rom,
            temperature: Some(21.0),
        }]);
        diagnostics.update(&SampleDiagnostics::default(), monitor.readings(), 46, 780);
        let probe = &diagnostics.probes[0];
        assert_eq!((probe.successes, probe.failures), (2, 2));
        assert_eq!(probe.current_error, None);
        assert_eq!(probe.last_error, Some(ReadFailure::Gpio));
        assert_eq!(probe.last_error_uptime_s, Some(31));
        assert_eq!(probe.last_success_uptime_s, Some(46));
        assert_eq!((diagnostics.cycles, diagnostics.failed_cycles), (4, 2));
        assert_eq!(diagnostics.current_error, None);
        assert_eq!(diagnostics.last_error, Some(ReadFailure::Gpio));
        diagnostics.update(&SampleDiagnostics::default(), &[], 61, 1);
        assert!(diagnostics.probes.is_empty());
        assert_eq!(diagnostics.last_error_uptime_s, Some(31));
    }

    #[test]
    fn counters_saturate_and_probe_history_follows_bounded_monitor() {
        let mut diagnostics = Diagnostics {
            cycles: u64::MAX,
            failed_cycles: u64::MAX,
            events: [u64::MAX; 11],
            ..Diagnostics::default()
        };
        let mut cycle = SampleDiagnostics::default();
        cycle.fail_batch(ReadFailure::NoPresence);
        diagnostics.update(&cycle, &[], 100, 1);
        assert_eq!(diagnostics.cycles, u64::MAX);
        assert_eq!(diagnostics.failed_cycles, u64::MAX);
        assert_eq!(diagnostics.events, [u64::MAX; 11]);
        assert!(diagnostics.probes.is_empty());
    }
}
