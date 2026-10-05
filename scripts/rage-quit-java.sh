#!/usr/bin/env bash
# Source this helper. It uses only libraries already approved in the app jar.
rage_quit_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
rage_quit_java=${RAGE_QUIT_JAVA_BIN:-java}
rage_quit_prepare_java() {
  local destination=$1 jar_file entry
  local -a jars
  shopt -s nullglob
  jars=("$rage_quit_repo"/rage-quit/target/rage-quit-*.jar)
  [[ ${#jars[@]} == 1 ]] || { printf 'Exactly one packaged application jar is required.\n' >&2; return 1; }
  jar_file=${jars[0]}
  # The JDK jar utility can be overridden together with java for local rehearsal.
  local jar_bin=${RAGE_QUIT_JAR_BIN:-jar}
  local -a entries
  mapfile -t entries < <("$jar_bin" tf "$jar_file" | grep -E '^BOOT-INF/lib/(sqlite-jdbc|snakeyaml)-[^/]+\.jar$')
  [[ ${#entries[@]} == 2 ]] || { printf 'Approved SQLite and YAML libraries are missing.\n' >&2; return 1; }
  (cd -- "$destination" && "$jar_bin" xf "$jar_file" "${entries[@]}")
  rage_quit_classpath="$destination/BOOT-INF/lib/*"
}
rage_quit_db() {
  "$rage_quit_java" --enable-native-access=ALL-UNNAMED --class-path "$rage_quit_classpath" \
    "$rage_quit_repo/scripts/rage-quit-db.java" "$@"
}
