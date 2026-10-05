#!/usr/bin/env bash
set -euo pipefail
umask 077
fail() { printf 'Backup refused: %s\n' "$1" >&2; exit 1; }
[[ $# == 5 && $5 == --offline-writer-stopped ]] || fail 'usage: DB SNAPSHOT.age RECIPIENTS_FILE START_DATE --offline-writer-stopped'
db=$1 output=$2 recipients=$3 date=$4
[[ $db == /* && $output == /* && $recipients == /* ]] || fail 'absolute paths required'
[[ -f $db && ! -L $db && -f $recipients && ! -L $recipients && ! -e $output && ! -L $output ]] || fail 'invalid input or existing output'
for suffix in -journal -wal -shm; do [[ ! -e $db$suffix && ! -L $db$suffix ]] || fail 'SQLite sidecar exists; stop/recover the writer first'; done
source "$(dirname -- "${BASH_SOURCE[0]}")/rage-quit-java.sh"
scratch=$(mktemp -d)
encoded=$(mktemp -- "$(dirname -- "$output")/.rage-quit-encrypted.XXXXXX")
cleanup() { rm -f -- "$encoded"; rm -rf -- "$scratch"; }
trap cleanup EXIT
rage_quit_prepare_java "$scratch"
migration="$rage_quit_repo/rage-quit/src/main/resources/db/migration/V1.sql"
exec 9<"$db"
flock -n -x 9 || fail 'maintenance lock is already held'
rage_quit_db validate "$db" "$migration" "$date"
cp -- "$db" "$scratch/snapshot.sqlite"
chmod 600 "$scratch/snapshot.sqlite"
rage_quit_db compare "$db" "$migration" "$date" "$scratch/snapshot.sqlite"
age --encrypt --recipients-file "$recipients" --output "$encoded" "$scratch/snapshot.sqlite"
# Atomic no-clobber publication on the destination filesystem.
ln -T -- "$encoded" "$output"
printf 'Encrypted offline snapshot verified; off-node transfer and restore rehearsal still required.\n'
