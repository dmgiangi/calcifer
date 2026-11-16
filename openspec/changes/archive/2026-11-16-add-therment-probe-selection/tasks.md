# Tasks

## 1. Implement

- [x] 1.1 Add bounded Search ROM discovery, addressed sampling and protocol tests.
- [x] 1.2 Add persistent explicit selection, disabled migration and safe-change policy.
- [x] 1.3 Add MQTT/Home Assistant selector and per-probe monitoring with payload tests.
- [x] 1.4 Debounce per-probe failures (four stale samples, fault on five) and add
  Home Assistant health diagnostics.
- [x] 1.5 Add directional -15/+40 °C control cutouts without changing sensor values.

## 2. Verify and release

- [x] 2.1 Run focused host tests and private OTA build; record image fit. The
  final `multi-probe-1` build is 1,291,760 bytes, below the 1,966,080-byte slot;
  21 Rust tests and 10 Python tests passed, with formatting, strict OpenSpec and
  whitespace checks. Action 19 installed this release and hawkBit confirmed the
  matching image as successful.
- [x] 2.2 Document MQTT configuration, operation and hardware acceptance limits
  in `therment/README.md`; no physical behavior is claimed verified.
- [x] 2.3 The operator confirmed physical acceptance for selection persistence
  across restart, selected-probe-loss shutdown and compressor delay. The checks
  were operator-verified; no independent hardware evidence was recorded. A Home
  Assistant screenshot
  received 2026-11-16 shows 18.0 °C for `28f3e7459204023f`, 19.0 °C for
  `286d0743980f00bb`, and the latter as the selected control probe. The operator
  authorized an arbitrary choice for testing; use `286d0743980f00bb` without
  claiming its physical location. Physical probe-to-chamber mapping remains
  unidentified; software tests were not substituted for physical acceptance.
- [x] 2.4 Re-ran host tests (27 Rust and 10 Python passed) and built private image
  `probe-selection-debounce-2`: 1,301,328 bytes, within the 1,966,080-byte OTA
  slot. Strict OpenSpec, rustfmt and whitespace checks passed. The image was not
  published or installed.
- [x] 2.5 Changed probe sampling to every 15 seconds and installed
  `probe-interval-15s-1` as hawkBit action `20`. The 1,301,312-byte image fits
  the 1,966,080-byte slot; action status and installed version both confirm
  success. Host tests and formatting checks passed before deployment.