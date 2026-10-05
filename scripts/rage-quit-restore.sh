#!/usr/bin/env bash
set -euo pipefail
umask 077
fail() { printf 'Restore refused: %s\n' "$1" >&2; exit 1; }
[[ $# == 7 && $6 == --offline-writer-stopped && $7 == --replace-current ]] || fail 'usage: SNAPSHOT.age DB IDENTITY_FILE START_DATE RETAIN_DIRECTORY --offline-writer-stopped --replace-current'
snapshot=$1 db=$2 identity=$3 date=$4 retained=$5
for path in "$snapshot" "$db" "$identity" "$retained"; do [[ $path == /* && ! -L $path ]] || fail 'absolute non-symlink paths required'; done
[[ -f $snapshot && -f $db && -f $identity && ! -e $retained ]] || fail 'inputs must exist and retention directory must be new'
[[ $(( 8#$(stat -c %a -- "$identity") & 077 )) == 0 ]] || fail 'identity file must be private (0600 or stricter)'
for suffix in -journal -wal -shm; do [[ ! -e $db$suffix && ! -L $db$suffix ]] || fail 'SQLite sidecar exists; stop/recover the writer first'; done
source "$(dirname -- "${BASH_SOURCE[0]}")/rage-quit-java.sh"
# Staging beside the DB makes the final replacement atomic, even on a PVC.
scratch=$(mktemp -d -- "$(dirname -- "$db")/.rage-quit-restore.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT
rage_quit_prepare_java "$scratch"
migration="$rage_quit_repo/rage-quit/src/main/resources/db/migration/V1.sql"
exec 9<"$db"
flock -n -x 9 || fail 'maintenance lock is already held'
age --decrypt --identity "$identity" --output "$scratch/candidate.sqlite" "$snapshot" 2>/dev/null || fail 'decryption failed'
rage_quit_db validate "$scratch/candidate.sqlite" "$migration" "$date"
# Nothing in the current DB is overwritten until the candidate passes all gates.
mkdir -- "$retained"
chmod 700 "$retained"
cp -p -- "$db" "$retained/current.sqlite"
chmod 600 "$retained/current.sqlite"
chown --reference="$db" "$retained" "$retained/current.sqlite" "$scratch/candidate.sqlite"
chmod 600 "$scratch/candidate.sqlite"
cmp -s -- "$db" "$retained/current.sqlite" || fail 'pre-restore state could not be retained'
mv -T -- "$scratch/candidate.sqlite" "$db"
printf 'Validated snapshot restored; current state retained. Keep the writer stopped until acceptance.\n'
