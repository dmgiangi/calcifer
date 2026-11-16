//! Bounded, externally powered DS18B20 sampling and control-probe selection.

use std::fmt;

pub const MAX_PROBES: usize = 8;

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct Rom(pub [u8; 8]);

impl Rom {
    pub fn parse(raw: &str) -> Option<Self> {
        if raw.len() != 16 || !raw.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return None;
        }
        let mut bytes = [0; 8];
        for (byte, pair) in bytes.iter_mut().zip(raw.as_bytes().chunks_exact(2)) {
            let digit = |value: u8| match value {
                b'0'..=b'9' => value - b'0',
                b'a'..=b'f' => value - b'a' + 10,
                _ => value - b'A' + 10,
            };
            *byte = digit(pair[0]) << 4 | digit(pair[1]);
        }
        Self::from_storage(u64::from_le_bytes(bytes))
    }

    pub fn from_storage(value: u64) -> Option<Self> {
        let bytes = value.to_le_bytes();
        (bytes[0] == 0x28 && crc8(&bytes[..7]) == bytes[7]).then_some(Self(bytes))
    }

    pub fn storage_value(self) -> u64 {
        u64::from_le_bytes(self.0)
    }
}

impl fmt::Display for Rom {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        for byte in self.0 {
            write!(formatter, "{byte:02x}")?;
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Probe {
    pub rom: Rom,
    pub temperature: Option<f32>,
}

pub const FAILED_READ_LIMIT: u8 = 5;
const MAX_TRACKED_PROBES: usize = MAX_PROBES * (FAILED_READ_LIMIT as usize + 1) + 1;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProbeHealth {
    Ok,
    Stale,
    Fault,
}

impl ProbeHealth {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ok => "ok",
            Self::Stale => "stale",
            Self::Fault => "fault",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MonitoredProbe {
    pub rom: Rom,
    pub temperature: Option<f32>,
    pub consecutive_failures: u8,
    pub health: ProbeHealth,
    pub detected: bool,
}

impl MonitoredProbe {
    fn empty(rom: Rom) -> Self {
        Self {
            rom,
            temperature: None,
            consecutive_failures: 0,
            health: ProbeHealth::Stale,
            detected: false,
        }
    }
}

/// Retain last-known readings through transient failures, faulting after five.
pub struct ProbeMonitor {
    readings: Vec<MonitoredProbe>,
}

impl ProbeMonitor {
    pub fn new(selected: Option<Rom>) -> Self {
        let mut monitor = Self {
            readings: Vec::with_capacity(MAX_TRACKED_PROBES),
        };
        if let Some(rom) = selected {
            monitor.ensure_probe(rom);
        }
        monitor
    }

    pub fn update(&mut self, samples: &[Probe]) {
        for reading in &mut self.readings {
            if let Some(sample) = samples.iter().find(|sample| sample.rom == reading.rom) {
                reading.detected = true;
                if let Some(temperature) = sample.temperature.filter(|t| valid_temperature(*t)) {
                    reading.temperature = Some(temperature);
                    reading.consecutive_failures = 0;
                    reading.health = ProbeHealth::Ok;
                } else {
                    record_failure(reading);
                }
            } else {
                reading.detected = false;
                record_failure(reading);
            }
        }

        for sample in samples {
            if self
                .readings
                .iter()
                .any(|reading| reading.rom == sample.rom)
                || !self.make_room()
            {
                continue;
            }
            let mut reading = MonitoredProbe::empty(sample.rom);
            reading.detected = true;
            if let Some(temperature) = sample.temperature.filter(|t| valid_temperature(*t)) {
                reading.temperature = Some(temperature);
                reading.health = ProbeHealth::Ok;
            } else {
                record_failure(&mut reading);
            }
            self.readings.push(reading);
        }
    }

    /// A changed association must not immediately reuse that probe's old sample.
    pub fn reset_probe(&mut self, selected: Option<Rom>) {
        let Some(rom) = selected else { return };
        if let Some(reading) = self.readings.iter_mut().find(|reading| reading.rom == rom) {
            reading.temperature = None;
            reading.consecutive_failures = 0;
            reading.health = ProbeHealth::Stale;
            reading.detected = false;
        } else {
            self.ensure_probe(rom);
        }
    }

    pub fn temperature(&self, selected: Option<Rom>) -> Option<f32> {
        let selected = selected?;
        self.readings
            .iter()
            .find(|reading| reading.rom == selected)
            .and_then(|reading| reading.temperature)
    }

    pub fn readings(&self) -> &[MonitoredProbe] {
        &self.readings
    }

    fn ensure_probe(&mut self, rom: Rom) {
        if !self.readings.iter().any(|reading| reading.rom == rom) && self.make_room() {
            self.readings.push(MonitoredProbe::empty(rom));
        }
    }

    fn make_room(&mut self) -> bool {
        if self.readings.len() < MAX_TRACKED_PROBES {
            return true;
        }
        if let Some(index) = self
            .readings
            .iter()
            .position(|reading| !reading.detected && reading.health == ProbeHealth::Fault)
        {
            self.readings.remove(index);
            true
        } else {
            false
        }
    }
}

fn record_failure(reading: &mut MonitoredProbe) {
    reading.consecutive_failures = reading.consecutive_failures.saturating_add(1);
    if reading.consecutive_failures >= FAILED_READ_LIMIT {
        reading.temperature = None;
        reading.health = ProbeHealth::Fault;
    } else {
        reading.health = ProbeHealth::Stale;
    }
}

fn valid_temperature(temperature: f32) -> bool {
    temperature.is_finite() && (-55.0..=125.0).contains(&temperature)
}

pub trait Bus {
    type Error;

    fn reset(&mut self) -> Result<bool, Self::Error>;
    fn write_bit(&mut self, bit: bool) -> Result<(), Self::Error>;
    fn read_bit(&mut self) -> Result<bool, Self::Error>;

    /// Wait the maximum conversion time (750 ms for 12-bit resolution).
    /// The bus must remain powered; parasite-powered probes are not supported.
    fn wait_conversion(&mut self);
}

/// Sample a fresh batch without retaining any previous temperatures.
///
/// Search visits at most MAX_PROBES ROM paths, including unsupported families
/// and invalid CRCs. With more devices, only supported, valid addresses among
/// the first eight paths (least-significant-bit first, zero branch first) are
/// returned. This is not a complete inventory, and adding a device can change
/// which addresses fit. No retries or unbounded searches are performed.
///
/// A low completion poll after the wait is a conversion timeout: every detected
/// probe retains None, including devices whose scratchpad still contains 85 C.
/// A GPIO error rejects the whole batch through Err.
pub fn sample<B: Bus>(bus: &mut B) -> Result<Vec<Probe>, B::Error> {
    let mut probes = discover(bus)?;
    if probes.is_empty() || !bus.reset()? {
        return Ok(probes);
    }

    write_byte(bus, 0xcc)?; // Skip ROM: one broadcast conversion, never a read.
    write_byte(bus, 0x44)?;
    bus.wait_conversion();
    if !bus.read_bit()? {
        return Ok(probes);
    }

    for probe in &mut probes {
        if !bus.reset()? {
            break;
        }
        write_byte(bus, 0x55)?; // Match ROM isolates each scratchpad read.
        for byte in probe.rom.0 {
            write_byte(bus, byte)?;
        }
        write_byte(bus, 0xbe)?;
        let mut scratchpad = [0; 9];
        for byte in &mut scratchpad {
            *byte = read_byte(bus)?;
        }
        probe.temperature = decode_temperature(scratchpad);
    }
    Ok(probes)
}

fn discover<B: Bus>(bus: &mut B) -> Result<Vec<Probe>, B::Error> {
    let mut probes = Vec::with_capacity(MAX_PROBES);
    let mut previous = [0; 8];
    let mut last_discrepancy = 0;

    for _ in 0..MAX_PROBES {
        if !bus.reset()? {
            break;
        }
        write_byte(bus, 0xf0)?; // Search ROM.
        let mut address = [0; 8];
        let mut last_zero = 0;
        for number in 1..=64 {
            let bit = bus.read_bit()?;
            let complement = bus.read_bit()?;
            if bit && complement {
                // All participants disappeared, or the search was corrupted.
                return Ok(probes);
            }
            let index = (number - 1) / 8;
            let mask = 1 << ((number - 1) % 8);
            let direction = if bit != complement {
                bit
            } else {
                let direction = if number < last_discrepancy {
                    previous[index] & mask != 0
                } else {
                    number == last_discrepancy
                };
                if !direction {
                    last_zero = number;
                }
                direction
            };
            if direction {
                address[index] |= mask;
            }
            bus.write_bit(direction)?;
        }
        if let Some(rom) = Rom::from_storage(u64::from_le_bytes(address)) {
            if !probes.iter().any(|probe: &Probe| probe.rom == rom) {
                probes.push(Probe {
                    rom,
                    temperature: None,
                });
            }
        }
        previous = address;
        last_discrepancy = last_zero;
        if last_discrepancy == 0 {
            break;
        }
    }
    Ok(probes)
}

fn write_byte<B: Bus>(bus: &mut B, byte: u8) -> Result<(), B::Error> {
    for bit in 0..8 {
        bus.write_bit(byte & (1 << bit) != 0)?;
    }
    Ok(())
}

fn read_byte<B: Bus>(bus: &mut B) -> Result<u8, B::Error> {
    let mut byte = 0;
    for bit in 0..8 {
        if bus.read_bit()? {
            byte |= 1 << bit;
        }
    }
    Ok(byte)
}

fn crc8(bytes: &[u8]) -> u8 {
    let mut crc = 0;
    for &byte in bytes {
        let mut value = byte;
        for _ in 0..8 {
            let mix = (crc ^ value) & 1;
            crc >>= 1;
            if mix != 0 {
                crc ^= 0x8c;
            }
            value >>= 1;
        }
    }
    crc
}

fn decode_temperature(scratchpad: [u8; 9]) -> Option<f32> {
    if scratchpad.iter().all(|&byte| byte == 0)
        || scratchpad.iter().all(|&byte| byte == 0xff)
        || crc8(&scratchpad[..8]) != scratchpad[8]
    {
        return None;
    }
    let raw = i16::from_le_bytes([scratchpad[0], scratchpad[1]]);
    let temperature = f32::from(raw) / 16.0;
    (-55.0..=125.0)
        .contains(&temperature)
        .then_some(temperature)
}

/// Clearing or changing the selection requires disabled control and safe outputs.
/// A detected probe may be selected even if its current temperature is invalid.
pub fn selection_request(
    raw: &str,
    enabled: bool,
    outputs_off: bool,
    probes: &[Probe],
) -> Result<Option<Rom>, &'static str> {
    if enabled || !outputs_off {
        return Err("probe selection requires disabled control and outputs off");
    }
    if raw == "None" {
        return Ok(None);
    }
    let rom = Rom::parse(raw).ok_or("invalid DS18B20 ROM")?;
    if !probes.iter().any(|probe| probe.rom == rom) {
        return Err("probe is not detected");
    }
    Ok(Some(rom))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone)]
    struct Device {
        address: [u8; 8],
        scratchpad: [u8; 9],
    }

    // Separate CRC formulation for fixtures, plus published check vectors below.
    fn fixture_crc(bytes: &[u8]) -> u8 {
        bytes.iter().fold(0, |mut crc, byte| {
            crc ^= byte;
            for _ in 0..8 {
                crc = if crc & 1 == 0 {
                    crc >> 1
                } else {
                    (crc >> 1) ^ 0x8c
                };
            }
            crc
        })
    }

    impl Device {
        fn new(family: u8, serial: u64, temperature: f32) -> Self {
            let mut address = [0; 8];
            address[0] = family;
            address[1..7].copy_from_slice(&serial.to_le_bytes()[..6]);
            address[7] = fixture_crc(&address[..7]);
            let [low, high] = ((temperature * 16.0) as i16).to_le_bytes();
            let mut scratchpad = [low, high, 0x4b, 0x46, 0x7f, 0xff, 0x0c, 0x10, 0];
            scratchpad[8] = fixture_crc(&scratchpad[..8]);
            Self {
                address,
                scratchpad,
            }
        }

        fn rom(&self) -> Rom {
            Rom(self.address)
        }
    }

    #[derive(Clone, Copy, Debug)]
    enum State {
        Command { rom: bool },
        Search { bit: usize, phase: u8 },
        Match { bytes: [u8; 8], index: usize },
        Conversion,
        Scratchpad { bit: usize },
    }

    // Models wired-AND bit slots, including both Search ROM responses and the
    // master's branch choice. A mistaken broadcast read mixes scratchpad bits.
    struct FakeBus {
        devices: Vec<Device>,
        selected: Vec<usize>,
        state: State,
        input: u8,
        input_bits: u8,
        operations: usize,
        fail_at: Option<usize>,
        absent_on_reset: Option<usize>,
        abort_search_at: Option<usize>,
        resets: usize,
        searches: usize,
        skips: usize,
        conversions: Vec<Vec<Rom>>,
        matches: Vec<Rom>,
        reads: Vec<Vec<Rom>>,
        waits: usize,
        waited: bool,
        polls: usize,
        complete: bool,
    }

    impl FakeBus {
        fn new(devices: Vec<Device>) -> Self {
            Self {
                devices,
                selected: Vec::new(),
                state: State::Command { rom: true },
                input: 0,
                input_bits: 0,
                operations: 0,
                fail_at: None,
                absent_on_reset: None,
                abort_search_at: None,
                resets: 0,
                searches: 0,
                skips: 0,
                conversions: Vec::new(),
                matches: Vec::new(),
                reads: Vec::new(),
                waits: 0,
                waited: false,
                polls: 0,
                complete: true,
            }
        }

        fn tick(&mut self) -> Result<(), &'static str> {
            self.operations += 1;
            if self.fail_at == Some(self.operations) {
                Err("GPIO failure")
            } else {
                Ok(())
            }
        }

        fn selected_roms(&self) -> Vec<Rom> {
            self.selected
                .iter()
                .map(|&index| self.devices[index].rom())
                .collect()
        }

        fn command(&mut self, byte: u8) {
            self.state = match self.state {
                State::Command { rom: true } => match byte {
                    0xf0 => {
                        self.searches += 1;
                        State::Search { bit: 0, phase: 0 }
                    }
                    0xcc => {
                        self.skips += 1;
                        State::Command { rom: false }
                    }
                    0x55 => State::Match {
                        bytes: [0; 8],
                        index: 0,
                    },
                    _ => panic!("unexpected ROM command: {byte:02x}"),
                },
                State::Command { rom: false } => match byte {
                    0x44 => {
                        self.conversions.push(self.selected_roms());
                        self.waited = false;
                        State::Conversion
                    }
                    0xbe => {
                        assert!(self.waited && self.polls > 0 && self.complete);
                        self.reads.push(self.selected_roms());
                        State::Scratchpad { bit: 0 }
                    }
                    _ => panic!("unexpected function command: {byte:02x}"),
                },
                State::Match { mut bytes, index } => {
                    bytes[index] = byte;
                    if index == 7 {
                        self.matches.push(Rom(bytes));
                        self.selected
                            .retain(|&index| self.devices[index].address == bytes);
                        State::Command { rom: false }
                    } else {
                        State::Match {
                            bytes,
                            index: index + 1,
                        }
                    }
                }
                state => panic!("unexpected command in {state:?}"),
            };
        }
    }

    impl Bus for FakeBus {
        type Error = &'static str;

        fn reset(&mut self) -> Result<bool, Self::Error> {
            self.tick()?;
            self.resets += 1;
            self.input = 0;
            self.input_bits = 0;
            self.state = State::Command { rom: true };
            self.selected = if self.absent_on_reset == Some(self.resets) {
                Vec::new()
            } else {
                (0..self.devices.len()).collect()
            };
            Ok(!self.selected.is_empty())
        }

        fn write_bit(&mut self, value: bool) -> Result<(), Self::Error> {
            self.tick()?;
            if let State::Search { bit, phase } = self.state {
                assert_eq!(phase, 2);
                assert!(bit < 64);
                self.selected.retain(|&index| {
                    (self.devices[index].address[bit / 8] & (1 << (bit % 8)) != 0) == value
                });
                self.state = State::Search {
                    bit: bit + 1,
                    phase: 0,
                };
                return Ok(());
            }
            if value {
                self.input |= 1 << self.input_bits;
            }
            self.input_bits += 1;
            if self.input_bits == 8 {
                let byte = self.input;
                self.input = 0;
                self.input_bits = 0;
                self.command(byte);
            }
            Ok(())
        }

        fn read_bit(&mut self) -> Result<bool, Self::Error> {
            self.tick()?;
            match self.state {
                State::Search { bit, phase } => {
                    assert!(bit < 64 && phase < 2);
                    let response = self.abort_search_at == Some(bit)
                        || self.selected.iter().all(|&index| {
                            let value =
                                self.devices[index].address[bit / 8] & (1 << (bit % 8)) != 0;
                            if phase == 0 { value } else { !value }
                        });
                    self.state = State::Search {
                        bit,
                        phase: phase + 1,
                    };
                    Ok(response)
                }
                State::Conversion => {
                    assert!(self.waited, "completion must be polled after waiting");
                    self.polls += 1;
                    Ok(self.complete)
                }
                State::Scratchpad { bit } => {
                    assert!(bit < 72);
                    let response = self.selected.iter().all(|&index| {
                        self.devices[index].scratchpad[bit / 8] & (1 << (bit % 8)) != 0
                    });
                    self.state = State::Scratchpad { bit: bit + 1 };
                    Ok(response)
                }
                state => panic!("unexpected read in {state:?}"),
            }
        }

        fn wait_conversion(&mut self) {
            assert!(matches!(self.state, State::Conversion));
            self.waits += 1;
            self.waited = true;
        }
    }

    #[test]
    fn rom_text_storage_and_crc_contract() {
        assert_eq!(crc8(b"123456789"), 0xa1);
        let rom = Rom::parse("280000000000001e").unwrap();
        assert_eq!(rom.0, [0x28, 0, 0, 0, 0, 0, 0, 0x1e]);
        assert_eq!(rom.storage_value(), 0x1e00_0000_0000_0028);
        assert_eq!(Rom::from_storage(rom.storage_value()), Some(rom));
        assert_eq!(rom.to_string(), "280000000000001e");
        assert_eq!(Rom::parse("280000000000001E"), Some(rom));
        for invalid in [
            "",
            "None",
            "280000000000001",
            "280000000000001ee",
            " 280000000000001e",
            "280000000000001e ",
            "280000000000001g",
            "280000000000001f",
            "0000000000000000",
            "éééééééé",
        ] {
            assert_eq!(Rom::parse(invalid), None, "{invalid:?}");
        }
        let unsupported = Device::new(0x10, 7, 20.0).rom();
        assert_eq!(Rom::parse(&unsupported.to_string()), None);
        assert_eq!(Rom::from_storage(unsupported.storage_value()), None);
        assert_eq!(Rom::from_storage(rom.storage_value() ^ (1 << 56)), None);
    }

    #[test]
    fn colliding_devices_use_one_conversion_and_isolated_match_rom_reads() {
        let devices = vec![
            Device::new(0x28, 1, 21.5),
            Device::new(0x28, 3, -10.125),
            Device::new(0x28, 5, 37.0625),
            Device::new(0x28, 0x0100_0000_0001, 0.0),
        ];
        let expected: Vec<_> = devices
            .iter()
            .zip([21.5, -10.125, 37.0625, 0.0])
            .map(|(device, temperature)| (device.rom(), Some(temperature)))
            .collect();
        let mut bus = FakeBus::new(devices);
        let probes = sample(&mut bus).unwrap();
        assert_eq!(probes.len(), expected.len());
        for (rom, temperature) in &expected {
            let probe = probes.iter().find(|probe| probe.rom == *rom).unwrap();
            assert_eq!(probe.temperature, *temperature);
        }
        assert_eq!(bus.searches, expected.len());
        assert_eq!(bus.skips, 1);
        assert_eq!(bus.conversions.len(), 1);
        assert_eq!(bus.conversions[0].len(), expected.len());
        assert_eq!((bus.waits, bus.polls), (1, 1));
        assert_eq!(bus.matches.len(), probes.len());
        for ((probe, matched), read) in probes.iter().zip(&bus.matches).zip(&bus.reads) {
            assert_eq!(probe.rom, *matched);
            assert_eq!(read, &vec![probe.rom]);
        }
    }

    #[test]
    fn empty_unsupported_and_bad_rom_crc_are_not_probes() {
        let mut empty = FakeBus::new(Vec::new());
        assert!(sample(&mut empty).unwrap().is_empty());
        assert_eq!(empty.resets, 1);
        assert_eq!(empty.waits, 0);

        let unsupported = Device::new(0x10, 1, 10.0);
        let mut bad_crc = Device::new(0x28, 2, 20.0);
        bad_crc.address[7] ^= 1;
        let valid = Device::new(0x28, 3, 30.0);
        let rom = valid.rom();
        let mut bus = FakeBus::new(vec![unsupported.clone(), bad_crc.clone(), valid]);
        assert_eq!(
            sample(&mut bus).unwrap(),
            vec![Probe {
                rom,
                temperature: Some(30.0),
            }]
        );
        assert_eq!(bus.searches, 3);
        assert_eq!(bus.matches, vec![rom]);

        let mut bus = FakeBus::new(vec![unsupported, bad_crc]);
        assert!(sample(&mut bus).unwrap().is_empty());
        assert!(bus.conversions.is_empty());
    }

    #[test]
    fn bad_scratchpads_are_isolated_without_discarding_other_probes() {
        let mut devices: Vec<_> = (0..8).map(|id| Device::new(0x28, id, 20.0)).collect();
        devices[0].scratchpad[8] ^= 1;
        devices[1].scratchpad = [0; 9]; // Has a valid CRC but is not a reading.
        devices[2].scratchpad = [0xff; 9];
        devices[3].scratchpad = Device::new(0x28, 3, 125.0625).scratchpad;
        devices[4].scratchpad = Device::new(0x28, 4, -55.0625).scratchpad;
        devices[5].scratchpad = Device::new(0x28, 5, -55.0).scratchpad;
        devices[6].scratchpad = Device::new(0x28, 6, 125.0).scratchpad;
        let roms: Vec<_> = devices.iter().map(Device::rom).collect();
        let mut bus = FakeBus::new(devices);
        let probes = sample(&mut bus).unwrap();
        assert_eq!(probes.len(), 8);
        let expected = [
            None,
            None,
            None,
            None,
            None,
            Some(-55.0),
            Some(125.0),
            Some(20.0),
        ];
        for (rom, expected) in roms.iter().zip(expected) {
            let probe = probes.iter().find(|probe| probe.rom == *rom).unwrap();
            assert_eq!(probe.temperature, expected);
        }
        assert_eq!(bus.reads.len(), 8);
    }

    #[test]
    fn conversion_completion_gates_power_on_value_and_stale_readings() {
        let device = Device::new(0x28, 1, 85.0);
        let rom = device.rom();
        let mut bus = FakeBus::new(vec![device]);
        assert_eq!(sample(&mut bus).unwrap()[0].temperature, Some(85.0));
        bus.complete = false;
        let reads = bus.reads.len();
        assert_eq!(
            sample(&mut bus).unwrap(),
            vec![Probe {
                rom,
                temperature: None
            }]
        );
        assert_eq!(bus.reads.len(), reads);
        bus.devices[0].scratchpad = Device::new(0x28, 1, 21.0).scratchpad;
        assert_eq!(sample(&mut bus).unwrap()[0].temperature, None);
        assert_eq!(bus.reads.len(), reads);
    }

    #[test]
    fn lost_presence_never_returns_a_previous_temperature() {
        for missing_reset in [2, 3] {
            let device = Device::new(0x28, 1, 22.0);
            let rom = device.rom();
            let mut bus = FakeBus::new(vec![device]);
            bus.absent_on_reset = Some(missing_reset);
            assert_eq!(
                sample(&mut bus).unwrap(),
                vec![Probe {
                    rom,
                    temperature: None
                }]
            );
            assert!(bus.reads.is_empty());
        }
        let mut bus = FakeBus::new(vec![Device::new(0x28, 1, 22.0)]);
        assert_eq!(sample(&mut bus).unwrap()[0].temperature, Some(22.0));
        bus.devices.clear();
        assert!(sample(&mut bus).unwrap().is_empty());
    }

    #[test]
    fn enumeration_is_bounded_even_when_no_device_is_supported() {
        let mut devices: Vec<_> = (0..20).map(|id| Device::new(0x10, id, 20.0)).collect();
        // The unsupported family's zero branch is visited before family 0x28.
        devices.push(Device::new(0x28, 1, 21.0));
        let mut bus = FakeBus::new(devices);
        assert!(sample(&mut bus).unwrap().is_empty());
        assert_eq!(bus.searches, MAX_PROBES);
        assert!(bus.conversions.is_empty());

        let devices = (0..20).map(|id| Device::new(0x28, id, 20.0)).collect();
        let mut bus = FakeBus::new(devices);
        let probes = sample(&mut bus).unwrap();
        assert_eq!(bus.searches, MAX_PROBES);
        assert_eq!(probes.len(), MAX_PROBES);
        let mut unique: Vec<_> = probes.iter().map(|probe| probe.rom).collect();
        unique.sort();
        unique.dedup();
        assert_eq!(unique.len(), MAX_PROBES);
        assert!(probes.iter().all(|probe| probe.temperature == Some(20.0)));
    }

    #[test]
    fn disappearing_search_participants_abort_without_conversion() {
        let mut bus = FakeBus::new(vec![Device::new(0x28, 1, 20.0)]);
        bus.abort_search_at = Some(11);
        assert!(sample(&mut bus).unwrap().is_empty());
        assert_eq!(bus.searches, 1);
        assert!(bus.conversions.is_empty());
    }

    #[test]
    fn every_gpio_failure_rejects_the_entire_batch() {
        let devices = vec![Device::new(0x28, 1, 20.0), Device::new(0x28, 3, 30.0)];
        let mut successful = FakeBus::new(devices.clone());
        sample(&mut successful).unwrap();
        // Includes failure after one good scratchpad, so partial batches cannot
        // accidentally escape as successful results.
        for operation in 1..=successful.operations {
            let mut bus = FakeBus::new(devices.clone());
            bus.fail_at = Some(operation);
            assert_eq!(sample(&mut bus), Err("GPIO failure"), "slot {operation}");
        }
    }

    #[test]
    fn monitor_temperature_requires_the_exact_selected_probe() {
        let first = Device::new(0x28, 1, 20.0).rom();
        let second = Device::new(0x28, 2, 30.0).rom();
        let missing = Device::new(0x28, 3, 40.0).rom();
        let mut monitor = ProbeMonitor::new(Some(first));
        monitor.update(&[
            Probe {
                rom: first,
                temperature: None,
            },
            Probe {
                rom: second,
                temperature: Some(30.0),
            },
        ]);
        assert_eq!(monitor.temperature(None), None);
        assert_eq!(monitor.temperature(Some(first)), None);
        assert_eq!(monitor.temperature(Some(missing)), None);
        assert_eq!(monitor.temperature(Some(second)), Some(30.0));
    }

    #[test]
    fn selection_requires_safe_state_and_a_valid_detected_address() {
        let rom = Device::new(0x28, 1, 20.0).rom();
        let probes = [Probe {
            rom,
            temperature: None,
        }];
        let raw = rom.to_string();
        assert_eq!(selection_request("None", false, true, &[]), Ok(None));
        assert_eq!(selection_request(&raw, false, true, &probes), Ok(Some(rom)));
        assert_eq!(
            selection_request(&raw.to_uppercase(), false, true, &probes),
            Ok(Some(rom))
        );
        for (enabled, outputs_off) in [(true, true), (true, false), (false, false)] {
            for request in [raw.as_str(), "None"] {
                assert!(selection_request(request, enabled, outputs_off, &probes).is_err());
            }
        }
        let unknown = Device::new(0x28, 2, 20.0).rom().to_string();
        let unsupported = Device::new(0x10, 1, 20.0).rom();
        let corrupt = Rom([0x28, 1, 0, 0, 0, 0, 0, 0]);
        for invalid in ["none", "", "None ", "28", &unknown, &format!(" {raw}")] {
            assert!(selection_request(invalid, false, true, &probes).is_err());
        }
        // Even a caller-provided inventory cannot authorize an invalid ROM.
        for invalid in [unsupported, corrupt] {
            let inventory = [Probe {
                rom: invalid,
                temperature: Some(20.0),
            }];
            assert!(selection_request(&invalid.to_string(), false, true, &inventory).is_err());
        }
    }

    #[test]
    fn monitor_resets_failure_streak_and_faults_on_fifth_consecutive_failure() {
        let rom = Device::new(0x28, 1, 20.0).rom();
        let mut monitor = ProbeMonitor::new(None);
        monitor.update(&[Probe {
            rom,
            temperature: Some(20.0),
        }]);
        for failures in 1..=2 {
            monitor.update(&[Probe {
                rom,
                temperature: None,
            }]);
            let reading = &monitor.readings()[0];
            assert_eq!(reading.temperature, Some(20.0));
            assert_eq!(reading.consecutive_failures, failures);
            assert_eq!(reading.health, ProbeHealth::Stale);
            assert_eq!(monitor.temperature(Some(rom)), Some(20.0));
        }
        monitor.update(&[Probe {
            rom,
            temperature: Some(21.0),
        }]);
        let reading = &monitor.readings()[0];
        assert_eq!(reading.temperature, Some(21.0));
        assert_eq!(reading.consecutive_failures, 0);
        assert_eq!(reading.health, ProbeHealth::Ok);

        for failures in 1..FAILED_READ_LIMIT {
            monitor.update(&[Probe {
                rom,
                temperature: None,
            }]);
            let reading = &monitor.readings()[0];
            assert_eq!(reading.temperature, Some(21.0));
            assert_eq!(reading.consecutive_failures, failures);
            assert_eq!(reading.health, ProbeHealth::Stale);
            assert_eq!(monitor.temperature(Some(rom)), Some(21.0));
        }
        monitor.update(&[Probe {
            rom,
            temperature: None,
        }]);
        let reading = &monitor.readings()[0];
        assert_eq!(reading.temperature, None);
        assert_eq!(reading.consecutive_failures, FAILED_READ_LIMIT);
        assert_eq!(reading.health, ProbeHealth::Fault);
        assert_eq!(monitor.temperature(Some(rom)), None);

        monitor.update(&[Probe {
            rom,
            temperature: Some(22.0),
        }]);
        let reading = &monitor.readings()[0];
        assert_eq!(reading.temperature, Some(22.0));
        assert_eq!(reading.consecutive_failures, 0);
        assert_eq!(reading.health, ProbeHealth::Ok);
    }

    #[test]
    fn monitor_counts_absence_and_keeps_operating_envelope_values_valid() {
        let rom = Device::new(0x28, 1, 20.0).rom();
        let mut monitor = ProbeMonitor::new(Some(rom));
        monitor.update(&[Probe {
            rom,
            temperature: Some(-20.0),
        }]);
        assert_eq!(monitor.readings()[0].health, ProbeHealth::Ok);
        assert_eq!(monitor.temperature(Some(rom)), Some(-20.0));
        for _ in 0..FAILED_READ_LIMIT - 1 {
            monitor.update(&[]);
            assert_eq!(monitor.temperature(Some(rom)), Some(-20.0));
        }
        monitor.update(&[]);
        assert_eq!(monitor.readings()[0].health, ProbeHealth::Fault);
        assert_eq!(monitor.temperature(Some(rom)), None);
    }
}
