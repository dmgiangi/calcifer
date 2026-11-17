# Therment OTA operations

Status: the core OTA path has been exercised with normal releases and a
controlled boot-failure rollback. On 2026-11-17, action `21` installed
`probe-timing-diagnostics-1`; hawkBit and live MQTT confirmed the matching
version. Initial diagnostics reported 13 successful readings per probe and no
errors through 191 seconds uptime. The release keeps the 15-second sampling
wait and adds protected OneWire timing and boot-scoped diagnostics. A Home
Assistant screenshot received on 2026-11-16 shows both probes with valid
readings and `286d0743980f00bb` selected.
The operator subsequently confirmed physical acceptance of selection persistence
across restart, selected-probe-loss shutdown and compressor delay. The test ROM
was chosen arbitrarily, so its physical location remains unidentified. Settings
preservation and USB recovery still need acceptance. The operator reports no
Internet reachability to the update service.

## Architecture and security

hawkBit runs on `calcifer-home` at `https://ota.calcifer.tech` (LAN DNS:
`192.168.0.102`). The route accepts the `192.168.0.0/24` network; router and
NAT paths still require independent verification. TLS certificates are managed
by cert-manager. The ESP32 needs a synchronized clock for certificate
validation; failed DNS, clock synchronization or TLS verification prevents OTA,
not autonomous temperature regulation.

The workstation builds an OTA-enabled, versioned image, uploads it to hawkBit,
assigns it to `therment-01`, publishes a non-retained MQTT check request, and
observes the matching action. The server and local backup volumes are not
integrity- or confidentiality-protected against node or disk theft. The image
contains Wi-Fi, MQTT and OTA target credentials: build only on a trusted host;
never commit images or send them to public artifacts, CI or untrusted storage.
The dedicated SOPS target token is decrypted by the build process into generated
build files and the firmware. Protect and clean the ignored build directory like
a credential. Server backup and recovery are documented in
[hawkBit operations](../docs/hawkbit-operations.md).

The device validates the TLS certificate and hostname, refuses redirects and
other origins, checks the target token, image size and SHA-256, and writes only
the inactive slot. ESP-IDF validates the image before selecting it. A checksum
is not an independent signature: the design trusts the private server and
release publisher. It waits for both relays to turn off, inhibits relay
activation, rechecks the assignment and only then selects the new slot and
restarts. The new image is confirmed after safe GPIO, NVS and one local control
cycle; a restarting startup watchdog and the rollback-enabled bootloader limit
unconfirmed startup. After reboot, the device reports success only when its
embedded release version matches the pending action. A previous OTA-enabled
image can report a rollback; an older firmware without OTA cannot.

## MQTT and Home Assistant

Discovery is retained at `homeassistant/update/therment-01-ota/config`. The
entity uses the existing device availability topic and these OTA topics:

| Topic | Publisher | Purpose |
| --- | --- | --- |
| `therment/therment-01/ota/state` | Therment | Retained installed/available versions and update progress |
| `therment/therment-01/ota/attributes` | Therment | Retained phase and diagnostic outcome |
| `therment/therment-01/ota/set` | Home Assistant or publisher | Non-retained `INSTALL` requests an immediate check |
| `homeassistant/button/therment-01-ota-check/config` | Therment | Retained discovery for an always-available manual check button |

A check request contains no URL or credential. The device coalesces requests,
rate limits checks and uses the same authenticated update path as its periodic
polling. MQTT traffic and credentials are unencrypted on the LAN. Existing
broker ACLs are disabled; before enabling ACLs, allow Therment to write both
status topics and discovery and read the command topic, allow Home Assistant
to read discovery and status and write the command, allow `ota-publisher` to
write the command, and preserve all existing client and health-probe access.
The discovery and state schemas were checked against the installed Home
Assistant version, and the update entity reported the first installed release.
The new check button still needs to be tested on the device.

Home Assistant rejects `update.install` when the entity reports that no update
is available. The separate diagnostic button `Check for updates` therefore
provides an immediate check even when installed and available versions match;
it publishes the same non-retained command and does not bypass server or relay
safety checks.

## Build and publish

Source the ESP toolchain in the terminal first:

```sh
cd /persistent/clc/therment
source "$HOME/export-esp.sh"
```

The local operator script requires `cargo`, `espflash`, `sops`, the project's
age identity, Python with PyYAML, access to hawkBit over HTTPS and the LAN MQTT
broker. Once the first OTA firmware is installed and accepted, run from the
repository root with a unique version containing letters, numbers, dots,
underscores or hyphens:

```sh
python3 therment/scripts/ota-deploy.py RELEASE_VERSION --timeout 1800
```

The deliberate boot-failure test is restricted to the dedicated version and
must only be used with connected loads made safe/disconnected. It writes the
inactive OTA slot and intentionally lets the startup watchdog reset the device:

```sh
python3 therment/scripts/ota-deploy.py rollback-test-2 --boot-failure-test --timeout 1800
```

The deployment tool rejects this version without the explicit test flag and
rejects the flag for any other version. Never use this test mode for a normal
release.

This builds a release with the embedded version and SOPS target credential,
checks the image fits a 1,966,080-byte slot, uploads the image, assigns it to
`therment-01`, sends a QoS 1 non-retained MQTT request, and emits JSON events.
Existing versions are reused only when their artifact SHA-256 matches. A retry
reuses an existing matching action. Trigger failure is reported separately;
automatic polling may still complete the action. A timeout, failure or unknown
outcome exits nonzero. A later read-only query is available with:

```sh
python3 therment/scripts/ota-status.py ACTION_ID
```

Success requires a finished matching action and the same installed
distribution, reported by the device after local confirmation. A successful
upload or MQTT acknowledgement alone is not an update success. Credentials are
read through SOPS in memory; the command does not print them. The release image
is created in a private `/dev/shm` directory and deleted after the command;
secret-bearing Cargo build products remain under the ignored, private
`therment/target/ota-release` directory.

### Credential rotation

The device target token is embedded in the firmware and stored under SOPS at
`clusters/calcifer-home/apps/home-automation/hawkbit/therment-01-ota-credentials.sops.yaml`.
Rotating the server-side token before the device has the replacement prevents
it from authenticating to hawkBit. Prepare a controlled maintenance window and
a USB recovery path before changing it; the server and device must be updated
as one coordinated operation, and an older rollback image may still contain
the previous token. Do not print either token. Publisher credentials are
separate; rotate the SOPS secret and server configuration, reconcile Flux, and
verify the new account with a non-destructive request.

Wi-Fi and MQTT credentials are also embedded. Coordinate their rotation with
the corresponding network or broker change and retain USB recovery: an image
containing new credentials cannot reconnect until the network accepts them.
OTA uses HTTPS and its own target token, but a Wi-Fi credential mismatch also
prevents OTA recovery. Do not assume rollback restores connectivity after the
server-side credential has changed.

For a build check without publishing or changing the device:

```sh
cd /persistent/clc/therment
source "$HOME/export-esp.sh"
THERMENT_RELEASE_VERSION=ota-check ./scripts/build-ota-check.sh --features ota
```

Run host tests without device credentials or hardware:

```sh
cd /persistent/clc
python3 -m unittest discover -s therment/tests -p 'test_*.py'
cargo +stable test --manifest-path therment/tests/ota-policy/Cargo.toml \
  --target x86_64-unknown-linux-gnu --locked
```

## Released probe timing/diagnostic firmware

On 2026-11-17, `probe-timing-diagnostics-1` was prepared with interrupt-protected
OneWire timing and retained MQTT/Home Assistant diagnostics (see README.md).
The build-only OTA check succeeded: 1,322,144 bytes in a 1,966,080-byte slot.
All 35 Rust host tests and 8 Python OTA operator tests passed; source formatting,
diff whitespace and the OpenSpec change validated. The separate backup test
cannot import the pre-existing missing `scripts/hawkbit-local-backup.py`.

After the operator confirmed safe loads and explicitly authorized OTA, the
release was uploaded and installed as action `21`. Both the deployment command
and a separate read-only query reported `finished`/`success` with installed
version `probe-timing-diagnostics-1`; MQTT reported the same running version.

Read-only MQTT observation verified ten consecutive distinct live diagnostic
updates (cycles 4–13), in addition to startup observations. At uptime 191 seconds,
the counters reported 13 successes and zero failures for each probe, zero failed
cycles and zero categorized events; both probes remained `ok`. Temperatures
were 20.5–21.0 °C for `286d0743980f00bb` and 20.0–20.5 °C for
`28f3e7459204023f`. The selected control ROM remained `286d0743980f00bb`, and
availability returned to `online` after the expected OTA restart.

The retained diagnostic snapshot and all three retained Home Assistant
diagnostic discovery configurations were received with the expected state
topic. This verifies MQTT publication, not Home Assistant UI acceptance or
long-term reliability. The short initial observation does not rule out later
intermittent errors or electrical faults; diagnostics remain enabled to
identify their causes.

## USB migration and recovery

USB access resets the controller. Before writing flash, use a maintenance
window, make connected equipment safe, and verify actual relay levels during
reset and boot. Software GPIO initialization cannot control the boot-ROM or
reset-time pin state; external pull-ups and hardware behavior must be checked.

Inspection on 2026-11-16 found an ESP32 revision 1.0 with 4 MiB flash, NVS at
`0x9000`/`0x6000`, PHY at `0xf000`/`0x1000`, and a factory application at
`0x10000`/`0x3f0000`. The OTA layout preserves NVS and PHY offsets, uses
`0x10000` for OTA metadata, and has two `0x1e0000` app slots at `0x20000` and
`0x200000`. The factory app overlaps the new layout and will be overwritten.
The inspected original bootloader image had a valid checksum, but its rollback
setting was not established. The OTA-enabled bootloader has since demonstrated
rollback on the actual device during action `18`.

A USB migration must install the generated bootloader, OTA partition table and
first OTA-enabled application, while preserving NVS. Verify all offsets, the
image size, device identity and a working recovery plan before writing. A first
OTA slot has no known-good OTA fallback until a second image has been accepted.
After repeated device flashes, the user explicitly waived a protected copy of
the exact pre-migration firmware. No such backup was made, so recovery of that
exact image is not verified. Do not run a full flash erase. The migration,
normal OTA updates and boot-failure rollback have since been exercised. Physical
relay behavior, settings preservation and recovery from a failed flash remain
unverified; broader fault-injection cases are deferred.

When the connected loads are safe and the operator accepts replacement of the
factory image, build the `bootstrap-1` release locally without publishing it:

```sh
cd /persistent/clc/therment
source "$HOME/export-esp.sh"
umask 077
python3 - <<'PY'
import importlib.util, tempfile
spec = importlib.util.spec_from_file_location('deploy', 'scripts/ota-deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
with tempfile.TemporaryDirectory(prefix='therment-bootstrap-', dir='/dev/shm') as directory:
    image = deploy.build('bootstrap-1', directory)
    print('Bootstrap image bytes:', image.stat().st_size)
PY
```

Then run:

```sh
bash scripts/flash-ota-bootstrap.sh
```

The script requires the private `target/ota-release` build for `bootstrap-1`,
checks the generated OTA partition table, confirms the expected ESP32 MAC and
4 MiB flash, and then flashes the generated bootloader, table and `ota_0` app.
It erases `otadata` only; it does not erase NVS or PHY. The first running image
must be checked over USB and MQTT before applying any OTA release. Inspect logs
before sharing them and remove any credentials or other private data.

## Verification performed

The Home server is deployed and Flux reconciled it. LAN HTTPS, publisher and
target authentication, and rejection from a tested non-LAN WireGuard path were
verified. A synthetic target exercised assignment, DDI polling, artifact
download, SHA-256 and success feedback; the synthetic target, release and
artifact were deleted. The generated MQTT Update discovery and state schema
were accepted by the installed Home Assistant integration. The first physical
migration wrote the expected partition table and created the Home Assistant
entity. An initial TCP/IP initialization race was corrected and the bootstrap
remained connected after reflashing. The first real OTA update from
`bootstrap-1` to `acceptance-1` completed as action `11`; Home Assistant and a
separate hawkBit status query reported the matching installed version. Repeating
the deployment command reused action `11`. A second OTA update installed
`acceptance-2` as action `12` and added the manual check button. The actual Home
Assistant display/button interaction and physical sensor/relay behavior remain
unverified; broader interrupted-download and invalid-image fault injection is
deferred.

On 2026-11-16, an attempted rollback test for action `17` (`rollback-test-1`)
was initially inconclusive. hawkBit then reported the action as `retrieved`,
and retained MQTT state showed the device offline with the download at 78%; its
attributes simultaneously reported phase `downloading` and outcome `success`,
so that outcome was stale or contradictory and not evidence of completion. A
filtered, read-only serial capture showed HTTPS request failures and repeated
`SW_RESET` reports, but no definitive marker that the test image booted or that
the previous image was restored. Serial-port access was granted for diagnosis;
no flash write or new MQTT trigger was sent. Because opening the serial
interface may itself affect reset lines, the captured reset observations do not
by themselves prove a firmware rollback. Action 17 remains inconclusive; the
later dedicated test in action 18 provides separate rollback evidence below.

A later read-only hawkBit query reported action `17` as `error` (classified as
failure) and the server's installed distribution as `acceptance-recovery-1`.
This is consistent with the test image failing and the recovery release being
reported, but the installed distribution is server-side state and does not by
itself prove which partition is currently running on the device. The operator's
later serial log showed a manual power-on reset and no further reset through at
least 63 seconds; its generic ESP-IDF app-version string does not identify the
Therment release. At that point rollback was only likely, not physically
verified; action 18 below later verified it directly.

A follow-up 50-second `espflash monitor` session used `--no-reset` and explicit
no-reset operations before and after connecting. It showed no reset, ROM-startup
or loaded-partition markers; the first 30 seconds produced no lines and the next
20 seconds produced only three non-diagnostic lines. No reboot was observed
during that interval, but the lack of usable device output means this does not
prove the controller stayed running.

The operator then supplied a serial capture after a manual power reset. The
`POWERON_RESET` was expected; no further reset appeared in the supplied log,
which continued through at least 63 seconds. SNTP initialization completed,
but the OTA worker reported `clock not synchronized` at 0.709, 10.739 and
25.759 seconds, so those checks did not proceed to report a previous action or
poll for an assignment. Wi-Fi initially logged `Host is unreachable` while
joining the access point, then obtained an IP address and MQTT connected. At
51.039 seconds the ESP certificate bundle logged `Certificate validated`, an
indication that a later HTTPS connection got past certificate validation and
that the clock was likely usable by then; the log does not explicitly report
SNTP synchronization. The following `esp_idf_svc::ota: Dropped` is the OTA
handle's destructor log, not evidence of a rollback or successful update. No
download, image activation, reboot into a new image, or final action feedback is
shown. The reported serial output was therefore consistent with the device
waiting between OTA checks; action 17 remained inconclusive.

On 2026-11-16, a dedicated boot-failure build `rollback-test-2` was deployed as
action `18` while the operator had made the loads safe. The 1,262,544-byte image
booted from slot `0x200000`, logged the intentional unconfirmed-startup test,
and the task watchdog fired at 15.731 seconds. On the following boot, the
bootloader selected the previous slot at `0x20000`; no later reset marker was
seen during the serial monitoring interval. This directly confirms physical
bootloader rollback. After reconnecting, retained MQTT reported availability
`online`, installed/latest version `acceptance-recovery-1`, phase `idle`,
outcome `rollback`, and no update in progress. hawkBit reported action `18` as
`error`/failure and its installed distribution remained `acceptance-recovery-1`.
The recovery firmware logged one early feedback-request failure before MQTT
connected; its subsequent status and MQTT outcome show the rollback was
reported. The OTA deployment command exits nonzero for this expected failed
test action. Interrupted-download and invalid-image fault injection are
deferred; physical relay/compressor behavior, settings preservation and USB
recovery remain separate acceptance gaps.

On 2026-11-16, the operator confirmed connected loads were safe before deploying
`multi-probe-1` as action `19`. The 1,291,760-byte image fits the OTA slot and
hawkBit confirmed action `finished` with the same installed version. A read-only
MQTT check confirmed availability `online`, control `off`/`idle`, sensor `error`,
and selected probe `None`. Retained Home Assistant discovery contains the
`Control probe` selector with two ROM options (`286d0743980f00bb` and
`28f3e7459204023f`) and distinct temperature entities for both. At that time,
the retained readings for both probes were `null`; physical temperature
acquisition had not yet been verified. A later screenshot showed valid readings
and `286d0743980f00bb` selected, but did not identify which probe is physically
in the chamber.

Later on 2026-11-16, action `20` installed `probe-interval-15s-1`. The image was
1,301,312 bytes, within the 1,966,080-byte OTA slot. The deployment command and
a separate read-only status query both confirmed action `finished` and the
matching installed version. This release changes the probe sampling interval
from 5 to 15 seconds; host tests and formatting checks passed before deployment.
