#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/rage-quit-java.sh"
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
rage_quit_prepare_java "$scratch"
migration="$rage_quit_repo/rage-quit/src/main/resources/db/migration/V1.sql"
# Synthetic fixture date only; never used as a deployment default.
date=2000-01-01
rage_quit_db fixture "$scratch/source.sqlite" "$migration" "$date"
age-keygen -o "$scratch/identity" >/dev/null 2>&1
age-keygen -y "$scratch/identity" > "$scratch/recipients"
cp -- "$scratch/source.sqlite" "$scratch/current.sqlite"
chmod 600 "$scratch/current.sqlite"
bash "$rage_quit_repo/scripts/rage-quit-backup.sh" "$scratch/source.sqlite" "$scratch/snapshot.age" "$scratch/recipients" "$date" --offline-writer-stopped
bash "$rage_quit_repo/scripts/rage-quit-restore.sh" "$scratch/snapshot.age" "$scratch/current.sqlite" "$scratch/identity" "$date" "$scratch/retained" --offline-writer-stopped --replace-current
rage_quit_db compare "$scratch/source.sqlite" "$migration" "$date" "$scratch/current.sqlite"
rage_quit_db compare "$scratch/source.sqlite" "$migration" "$date" "$scratch/retained/current.sqlite"
[[ $(stat -c %a -- "$scratch/current.sqlite") == 600 ]]
[[ $(stat -c %u:%g -- "$scratch/current.sqlite") == "$(stat -c %u:%g -- "$scratch/source.sqlite")" ]]
printf 'not a database' > "$scratch/invalid.sqlite"
age --encrypt --recipients-file "$scratch/recipients" --output "$scratch/invalid.age" "$scratch/invalid.sqlite"
if bash "$rage_quit_repo/scripts/rage-quit-restore.sh" "$scratch/invalid.age" "$scratch/current.sqlite" "$scratch/identity" "$date" "$scratch/must-not-exist" --offline-writer-stopped --replace-current >/dev/null 2>&1; then
  printf 'Invalid snapshot was accepted.\n' >&2; exit 1
fi
[[ ! -e $scratch/must-not-exist ]]
rage_quit_db compare "$scratch/source.sqlite" "$migration" "$date" "$scratch/current.sqlite"
if bash "$rage_quit_repo/scripts/rage-quit-restore.sh" "$scratch/snapshot.age" "$scratch/current.sqlite" "$scratch/identity" 2000-01-02 "$scratch/wrong-date" --offline-writer-stopped --replace-current >/dev/null 2>&1; then
  printf 'Mismatched start date was accepted.\n' >&2; exit 1
fi
[[ ! -e $scratch/wrong-date ]]
rage_quit_db compare "$scratch/source.sqlite" "$migration" "$date" "$scratch/current.sqlite"
printf 'Synthetic encryption/restore, preserved records/settings/derived inputs, permissions and invalid-snapshot rejection passed.\n'
