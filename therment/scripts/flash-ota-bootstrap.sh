#!/usr/bin/env bash
# First OTA migration overwrites the old factory app. Run only with the
# connected equipment made safe and after reviewing OTA.md.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
target="$PWD/target/ota-release"
image="$target/xtensa-esp32-espidf/release/therment"
bootloader="$target/xtensa-esp32-espidf/release/bootloader.bin"
table="$target/xtensa-esp32-espidf/release/partition-table.bin"
version="$target/release-version"
test -f "$image" && test -f "$bootloader" && test -f "$table" && test -f "$version"
exec 9>"$target/.build.lock"
flock -n 9 || { printf '%s\n' 'An OTA build is running; retry after it finishes.' >&2; exit 1; }
test "$(cat "$version")" = 'bootstrap-1' || { printf '%s\n' 'Build bootstrap-1 before flashing.' >&2; exit 1; }
for source_file in Cargo.toml Cargo.lock build.rs sdkconfig.ota.defaults partitions-ota.csv; do
  if [[ "$source_file" -nt "$image" ]]; then
    printf '%s\n' 'Firmware inputs changed after the build; rebuild bootstrap-1.' >&2
    exit 1
  fi
done
if [[ -n "$(find src -type f -name '*.rs' -newer "$image" -print -quit)" ]]; then
  printf '%s\n' 'Firmware sources changed after the build; rebuild bootstrap-1.' >&2
  exit 1
fi
shopt -s nullglob
configs=("$target"/xtensa-esp32-espidf/release/build/esp-idf-sys-*/out/sdkconfig)
if [[ ${#configs[@]} -ne 1 ]] ||
   ! grep -Fxq 'CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y' "${configs[0]}" ||
   ! grep -Fxq 'CONFIG_ESP_TASK_WDT_PANIC=y' "${configs[0]}" ||
   ! grep -Fxq 'CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y' "${configs[0]}"; then
  printf '%s\n' 'Generated rollback, watchdog or flash configuration is invalid.' >&2
  exit 1
fi

# Validate the generated table without displaying firmware or credentials.
espflash partition-table --to-csv "$table" | python3 -c '
import csv, sys
rows = {r[0].strip(): r for r in csv.reader(line for line in sys.stdin if not line.startswith("#")) if r}
expected = {"nvs": ("0x9000", "0x6000"), "phy_init": ("0xf000", "0x1000"),
            "otadata": ("0x10000", "0x2000"), "ota_0": ("0x20000", "0x1e0000"),
            "ota_1": ("0x200000", "0x1e0000")}
for name, (offset, size) in expected.items():
    if name not in rows or int(rows[name][3], 0) != int(offset, 0) or int(rows[name][4], 0) != int(size, 0):
        raise SystemExit("Generated partition table does not match the verified layout")
print("Generated OTA partition offsets and sizes verified.")
'

board=$(espflash board-info --port /dev/ttyUSB0 --chip esp32)
printf '%s\n' "$board"
if ! printf '%s\n' "$board" | grep -qi '84:cc:a8:2c:15:20'; then
  printf '%s\n' 'Unexpected device identity; no flash write was attempted.' >&2
  exit 1
fi
if ! printf '%s\n' "$board" | grep -Eq 'Flash size:[[:space:]]*4MB'; then
  printf '%s\n' 'Unexpected flash size; no flash write was attempted.' >&2
  exit 1
fi

# Erase only the new otadata range; NVS and PHY are not erased. The old
# factory application is overwritten by the new metadata and app layout.
espflash flash --port /dev/ttyUSB0 --chip esp32 --flash-size 4mb \
  --bootloader "$bootloader" --partition-table "$table" \
  --target-app-partition ota_0 --erase-parts otadata "$image"
