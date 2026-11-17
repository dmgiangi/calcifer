# Therment

Therment is ESP32 firmware for a fermentation chamber. Temperature regulation
runs locally; Wi-Fi and MQTT connect the device to Home Assistant. The module
is part of Calcifer under `therment/`; run the module commands from this
directory. Flux deploys the Home services, not the firmware itself.

## Build prerequisites

On Linux, install Rust and Cargo via `rustup`, C/C++ build tools, Git, Python 3
with `venv` and `pip`, CMake, Ninja, Clang and the libudev development files.
For example, on Debian or Ubuntu:

```sh
sudo apt-get install build-essential git curl pkg-config libudev-dev clang libclang-dev python3 python3-pip python3-venv cmake ninja-build flex bison gperf libffi-dev libssl-dev
```

Install the Espressif ESP32 toolchain, `ldproxy` and `espflash`:

```sh
cargo install espup --version 0.19.0 --locked
espup install --targets esp32 --toolchain-version 1.97.0.0
cargo install ldproxy --version 0.3.5 --locked
cargo install espflash --version 4.7.0 --locked
```

Load the environment created by `espup` before building:

```sh
source "$HOME/export-esp.sh"
```

Use the chosen alternative path if the export file was installed elsewhere.
`rust-toolchain.toml` selects the local `esp` alias; `Cargo.toml` records the
installer (`espup` 0.19.0) and Xtensa Rust toolchain (1.97.0.0) separately.
The same metadata pins `ldproxy` 0.3.5 and `espflash` 4.7.0. `.cargo/config.toml`
selects `xtensa-esp32-espidf` and ESP-IDF v5.4.4. The locked firmware
dependencies build against this combination; re-run the build-only OTA check
after changing any of these pins. The first build downloads ESP-IDF and its
tools, requiring network access and disk space. `.embuild/` and `target/` are
generated, Git-ignored local data. `Cargo.lock` pins Rust dependencies.

## Device configuration

Copy `.wifi-credentials.example.toml` to `.wifi-credentials.toml` and
`.mqtt-credentials.example.toml` to `.mqtt-credentials.toml`, then supply the
values. The actual files are Git-ignored and must remain private. The Home
broker is `192.168.0.102:1883`, with MQTT user `therment`; see the
[MQTT operations guide](../docs/zigbee2mqtt-operations.md#therment-lan-mqtt)
for credential handling, topics and network restrictions. LAN MQTT does not use
TLS, so credentials and messages are transmitted in cleartext. Wi-Fi, MQTT and
OTA target credentials are embedded in OTA-enabled firmware; protect the
firmware and generated build files as secrets.

GPIO13 connects to the shared OneWire probe bus, GPIO17 to heating and GPIO5 to cooling.
The firmware assumes active-low relays. Verify the physical output levels during
reset and boot; software cannot control the boot-ROM phase.

## Temperature probes and control association

Use externally powered DS18B20 probes on the shared bus (not parasite power).
The firmware searches up to eight ROM paths per sample, validates addresses and
reads each probe individually. The refrigerator chamber alone controls both
relay decisions; the freezer probe is monitoring-only. Roles never follow bus
order, probe temperature or the number of connected probes. Probes are sampled
every 15 seconds.

After installing this firmware for the first time, control remains `off` until
an operator explicitly chooses a probe. Home Assistant discovers a **Control
probe** configuration selector and a **Probe <ROM> temperature** sensor for
each detected address. Warm one probe by hand to identify it before assigning
the chamber; entity names can then be customized in Home Assistant.

| MQTT topic | Purpose |
| --- | --- |
| `therment/therment-01/control_probe/set` | Non-retained command: detected 16-character hex ROM ID, or `None` to clear |
| `therment/therment-01/control_probe/state` | Retained, actually accepted association; `None` means unassigned |
| `therment/therment-01/probes/state` | Retained JSON map of ROM IDs to last valid temperature, or null when unavailable |
| `therment/therment-01/probes/status` | Retained JSON map of ROM IDs to `ok`, `stale` or `fault` |
| `therment/therment-01/probes/diagnostics` | Retained, boot-scoped bus and per-ROM failure diagnostics |
| `therment/therment-01/temperature` | Existing climate temperature: selected probe only |

Set climate mode to `off` and wait for action `idle` before changing the
selection. The firmware rejects changes while control is enabled or an output
is active, unknown/malformed addresses, and replayed retained selection commands. A
successful choice is saved in local NVS before acknowledgment; a rejected
choice leaves the published state unchanged. The selector includes a saved
missing probe so its association stays visible.

Changing the association requires a fresh sample and restarts the five-minute
compressor delay. Enable `auto` separately after verifying the chosen probe.
No selection means control cannot be enabled. A missing or invalid selected
probe retains its last valid temperature for up to four consecutive failed
samples (up to about 60 seconds). The sensor is marked `stale` during that grace
period; after the fifth failure it is marked `fault`, its temperature becomes
unavailable, and both outputs turn off at that control cycle (about 75 seconds
after the last valid sample). A later valid sample clears the fault and resumes
control. The
`Probe <ROM> status` diagnostic entity reports `ok`, `stale` or `fault`. A bus
I/O error counts as a failure for each tracked probe. No fallback to the freezer
occurs.

Valid readings are never clamped to the operating cutouts. Below -15 °C cooling
is inhibited; above +40 °C heating is inhibited. The opposite direction remains
eligible to move the temperature back toward the configured setpoint. Values
outside these cutouts are not, by themselves, probe faults.

The association survives restart and OTA in NVS; local control does not require
MQTT or Home Assistant. MQTT discovery/state are retained, commands are not.
MQTT 3.1.1 marks retained replays, not live deliveries of a retained publish;
always send selection commands without retention.
Missing/invalid probes are unavailable in Home Assistant, including previously
discovered probes absent after a restart. A valid stored association retains
the existing persisted `auto`/`off` behavior on later boots.

Build and host tests do not verify wiring, electrical reset levels, physical
shutdown, NVS durability or compressor timing. Keep loads safe during initial
installation, identification and physical acceptance; see [OTA.md](OTA.md).

## Probe diagnostics

OneWire timing-sensitive reset/bit sequences disable interrupts briefly; idle
recovery and the 750 ms conversion wait do not. Read sampling uses nominal
3 + 9 microsecond delays, leaving margin before the 15 microsecond deadline.
This reduces software jitter but does not verify electrical signal quality.
Use externally powered probes, a common ground and an external pull-up
(typically 4.7 kΩ to 3.3 V). The ESP32 internal pull-up is not a substitute.

MQTT `therment/therment-01/probes/diagnostics` is retained, replaced after every
sample and republished on reconnect. Schema version `1` includes:

- `firmware_version`, `counter_scope: boot`, `uptime_s` at the last sample,
  `cycle_duration_ms`, `cycles` and `failed_cycles`.
- `current_error`: an error in the latest cycle, or null after recovery.
  `last_error` and `last_error_uptime_s` retain historical evidence.
- `events`: saturating event counters. These count occurrences, not necessarily
  failed probes: one bus timeout may affect several probes. `not_detected`
  counts missing tracked probes with no more specific batch cause.
- `probes`: per-ROM `successes`, `failures`, `consecutive_failures`, `detected`,
  `health`, `current_error`, historical `last_error`, `last_error_uptime_s` and
  `last_success_uptime_s`. A stale retained temperature is not a successful read.

| Error | Meaning / first checks |
| --- | --- |
| `not_detected` | Tracked ROM missing from this search; compare bus events |
| `no_presence` | No presence detected after reset; check power, wiring, pull-up |
| `search_conflict` | Invalid search bit pair; possible disturbed/disappearing bus |
| `rom_crc` | Corrupt discovered address; likely communication integrity issue |
| `unsupported_family` | CRC-valid device is not a DS18B20 |
| `search_limit` | Eight ROM paths exhausted with branches remaining; incomplete inventory |
| `conversion_timeout` | Completion poll low after 750 ms; check power and communication |
| `scratchpad_crc` | Temperature packet CRC mismatch |
| `invalid_scratchpad` | All-zero or all-one packet, not a valid measurement |
| `out_of_range` | CRC-valid temperature outside -55 to +125 °C |
| `gpio` | GPIO operation failed; the whole batch is discarded |

Home Assistant discovers **Probe failed cycles** plus **Probe <ROM> failed
readings**, under diagnostic entities. Their attributes expose the details
above. Counters reset on reboot and are not written to NVS. Firmware version
and uptime help distinguish restarts; HA history can preserve counter trends.
Only the latest snapshot is retained by MQTT, not a complete error log. No
retries hide failures, and diagnostics never change the five-failure safety rule.
Host trace tests verify pulse sequencing, not actual ESP32 waveforms. Persistent
errors still require wiring/power checks and, if needed, a logic-analyzer trace.

## USB and OTA

The ESP32 has been migrated to the two-slot OTA partition table. A normal
`cargo build` uses the legacy `sdkconfig.defaults`; do not flash it over the
migrated device with default partition settings. The OTA profile, USB recovery,
release procedure and the tests still required are documented in [OTA.md](OTA.md).
The initial `bootstrap-1` firmware and OTA updates through `acceptance-2`
were installed while the connected loads were made safe. The operator later
confirmed physical acceptance of selection persistence across restart,
selected-probe-loss shutdown and compressor delay. The physical mapping of the
selected ROM to the chamber was not recorded; see [OTA.md](OTA.md).

## Python build environment

ESP-IDF requires a Python interpreter with `venv` and `ensurepip`. On Ubuntu,
install the matching `python3-venv` package (for example `python3.12-venv` for
Python 3.12). If the system interpreter lacks it, a managed Python with `uv` can
be selected for the current shell without changing system packages:

```sh
uv python install 3.12
therment_python_bin="${XDG_CACHE_HOME:-$HOME/.cache}/therment-python-bin"
mkdir -p "$therment_python_bin"
ln -sf "$(uv python find 3.12 --managed-python)" "$therment_python_bin/python3"
source "$HOME/export-esp.sh"
export PATH="$therment_python_bin:$PATH"
cargo build --locked
```

The ESP-IDF setup can download toolchains and dependencies on the first build.
Protect generated output: the resulting binary includes device credentials.
