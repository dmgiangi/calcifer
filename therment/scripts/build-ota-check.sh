#!/usr/bin/env bash
set -euo pipefail

# Keep generated firmware and defaults private: the firmware contains secrets.
umask 077
therment_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
partition_file="$therment_dir/partitions-ota.csv"
case "$partition_file" in
  *\"*|*$'\n'*|*$'\r'*)
    printf '%s\n' 'Unsupported characters in partition-table path.' >&2
    exit 1
    ;;
esac
mkdir -p "$therment_dir/target"
check_dir=$(mktemp -d "$therment_dir/target/ota-check.XXXXXXXX")
defaults_file="$check_dir/sdkconfig.generated.defaults"
cat "$therment_dir/sdkconfig.ota.defaults" > "$defaults_file"
printf 'CONFIG_PARTITION_TABLE_CUSTOM_FILENAME="%s"\n' "$partition_file" >> "$defaults_file"
printf 'Experimental OTA build directory: %s\n' "$check_dir"
printf '%s\n' 'Do not flash this experimental build until migration and hardware checks are complete.'
cd "$therment_dir"
CARGO_TARGET_DIR="$check_dir" ESP_IDF_SDKCONFIG_DEFAULTS="$defaults_file" \
  cargo build --locked "$@"
