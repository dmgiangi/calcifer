#!/usr/bin/env bash
set -euo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/rage-quit-java.sh"
image=${1:-calcifer-rage-quit:checked}
scratch=$(mktemp -d)
name="rage-quit-check-$(basename -- "$scratch")"
volume="${name}-data"
container="${name}-app"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  rm -rf -- "$scratch"
}
trap cleanup EXIT
rage_quit_prepare_java "$scratch"
migration="$rage_quit_repo/rage-quit/src/main/resources/db/migration/V1.sql"
date=2000-01-01 # synthetic container test, not an operator start date
{
  printf 'RAGE_QUIT_ISSUER=https://auth.calcifer.tech\nRAGE_QUIT_CLIENT_ID=rage-quit\nRAGE_QUIT_DATABASE=/data/rage-quit.sqlite\nRAGE_QUIT_START_DATE=%s\nPORT=8080\nRAGE_QUIT_CLIENT_SECRET=' "$date"
  openssl rand -hex 32
} > "$scratch/env"
docker volume create "$volume" >/dev/null
docker run --rm --network none --user 0:0 --mount "type=volume,source=$volume,target=/data" \
  --entrypoint sh "$image" -c 'chown 10001:10001 /data; chmod 700 /data' >/dev/null
start() {
  docker run -d --name "$container" --platform linux/amd64 --network none \
    --read-only --user 10001:10001 --cap-drop ALL --security-opt no-new-privileges \
    --memory 512m --cpus 1 --pids-limit 128 \
    --tmpfs /tmp:rw,nosuid,nodev,exec,size=64m,mode=1777 \
    --mount "type=volume,source=$volume,target=/data" --env-file "$scratch/env" "$image" >/dev/null
}
# --network none proves auth outages do not prevent startup or healthy probes.
# Probe via the existing local curl image sharing the application's namespace.
probe() {
  docker run --rm --network "container:$container" curlimages/curl:8.16.0 \
    --silent --fail --max-time 3 "http://127.0.0.1:8080/actuator/health/$1" >/dev/null 2>&1
}
wait_ready() {
  for attempt in {1..60}; do
    if probe readiness; then return; fi
    [[ $(docker inspect --format '{{.State.Running}}' "$container") == true ]] || break
    sleep 2
  done
  printf 'Container readiness failed (logs deliberately not printed).\n' >&2; return 1
}
start
wait_ready
probe liveness
[[ $(docker image inspect --format '{{.Architecture}}' "$image") == amd64 ]]
[[ $(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "$container") == true ]]
docker exec "$container" sh -c 'test "$(id -u)" = 10001; test -w /data; test -w /tmp; test ! -w /app; test ! -e /var/run/secrets/kubernetes.io/serviceaccount/token; find /tmp -type f -name "*sqlite*.so" | grep -q .'
docker stop --time 60 "$container" >/dev/null
docker cp "$container:/data/rage-quit.sqlite" "$scratch/first.sqlite" >/dev/null
[[ $(stat -c %a -- "$scratch/first.sqlite") == 600 ]]
rage_quit_db validate "$scratch/first.sqlite" "$migration" "$date"
docker rm "$container" >/dev/null
start
wait_ready
docker stop --time 60 "$container" >/dev/null
docker cp "$container:/data/rage-quit.sqlite" "$scratch/restarted.sqlite" >/dev/null
rage_quit_db compare "$scratch/first.sqlite" "$migration" "$date" "$scratch/restarted.sqlite"
docker rm "$container" >/dev/null
# A missing mandatory start date must terminate rather than initialize defaults.
sed '/^RAGE_QUIT_START_DATE=/d' "$scratch/env" > "$scratch/missing-date-env"
docker run -d --name "$container" --network none --read-only --user 10001:10001 \
  --cap-drop ALL --security-opt no-new-privileges --memory 512m \
  --tmpfs /tmp:rw,nosuid,nodev,exec,size=64m,mode=1777 --mount "type=volume,source=$volume,target=/data" \
  --env-file "$scratch/missing-date-env" "$image" >/dev/null
for ((attempt = 1; attempt <= 60; attempt++)); do
  [[ $(docker inspect --format '{{.State.Running}}' "$container") == true ]] || break
  sleep 1
done
[[ $(docker inspect --format '{{.State.Running}}' "$container") == false ]]
[[ $(docker inspect --format '{{.State.ExitCode}}' "$container") != 0 ]]
printf 'Read-only amd64 non-root JVM, SQLite native extraction/schema, restart persistence, issuer-outage probes and missing-date gate passed.\n'
