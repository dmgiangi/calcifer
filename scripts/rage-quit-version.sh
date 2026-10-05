#!/usr/bin/env bash
set -euo pipefail
# Stable or prerelease SemVer; build metadata is not a legal Docker tag.
version=${1-}
fail() { printf 'Invalid release version (SemVer without build metadata required).\n' >&2; exit 1; }
[[ ${#version} -le 128 ]] || fail
[[ $version =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-([0-9A-Za-z-]+\.)*[0-9A-Za-z-]+)?$ ]] || fail
if [[ $version == *-* ]]; then
  IFS=. read -r -a identifiers <<< "${version#*-}"
  for identifier in "${identifiers[@]}"; do
    [[ ! $identifier =~ ^[0-9]+$ || $identifier == 0 || $identifier != 0* ]] || fail
  done
fi
