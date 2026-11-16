#!/usr/bin/env bash
set -euo pipefail

image=${1:-authorization-server:runtime-smoke}
for tool in docker openssl curl; do
    command -v "$tool" >/dev/null || {
        printf 'Required tool is missing: %s\n' "$tool" >&2
        exit 2
    }
done
docker image inspect "$image" >/dev/null
platform=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")
user=$(docker image inspect --format '{{.Config.User}}' "$image")
[[ "$platform" == linux/amd64 ]] || {
    printf 'Expected linux/amd64 image, got %s\n' "$platform" >&2
    exit 1
}
[[ "$user" == nonroot:nonroot ]] || {
    printf 'Expected nonroot:nonroot image user, got %s\n' "$user" >&2
    exit 1
}

umask 077
scratch=$(mktemp -d)
# The generated key is a disposable fixture, but the container's non-root UID
# must be able to traverse the directory and read it through the bind mount.
chmod 0711 "$scratch"
container_name="authorization-server-runtime-smoke-$$"
started=false
cleanup() {
    if [[ "$started" == true ]]; then
        docker stop --time 2 "$container_name" >/dev/null 2>&1 || true
    fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$scratch/jwt-private-key.pem" \
    >/dev/null 2>&1
chmod 0444 "$scratch/jwt-private-key.pem"

docker run --platform linux/amd64 --detach --rm --name "$container_name" \
    --publish 127.0.0.1::8080 \
    --mount "type=bind,src=$scratch/jwt-private-key.pem,dst=/tmp/jwt-private-key.pem,readonly" \
    --env AUTH_ISSUER=https://smoke.example.invalid \
    --env AUTH_SIGNING_KEY_LOCATION=file:/tmp/jwt-private-key.pem \
    --env AUTH_STATE_ENABLED=false \
    --env AUTH_LOCAL_LOGIN_ENABLED=false \
    --env GOOGLE_CLIENT_ID=runtime-smoke \
    --env GOOGLE_CLIENT_SECRET=runtime-smoke-secret \
    --env GRAFANA_OIDC_CLIENT_SECRET=runtime-smoke-secret \
    --env GRAFANA_API_CLIENT_SECRET=runtime-smoke-secret \
    --env MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false \
    --env 'SPRING_APPLICATION_JSON={"identity":{"groups":["admin"],"users":{"smoke":{"email":"smoke@example.invalid","canonicalSubject":"user:smoke","groups":["admin"],"authenticationMethods":["google"]}}}}' \
    "$image" >/dev/null
started=true

container_user=$(docker inspect --format '{{.Config.User}}' "$container_name")
[[ "$container_user" == nonroot:nonroot ]] || {
    printf 'Container did not retain the non-root user\n' >&2
    exit 1
}
host_port=$(docker port "$container_name" 8080/tcp | sed -n '1s/.*://p')
[[ "$host_port" =~ ^[0-9]+$ ]] || {
    printf 'Could not determine the local health-check port\n' >&2
    exit 1
}

healthy=false
for _ in {1..45}; do
    if ! docker inspect --format '{{.State.Running}}' "$container_name" 2>/dev/null | grep -qx true; then
        break
    fi
    if curl --fail --silent "http://127.0.0.1:$host_port/actuator/health/liveness" \
        | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        healthy=true
        break
    fi
    sleep 1
done
[[ "$healthy" == true ]] || {
    printf 'Authorization server failed its liveness check\n' >&2
    exit 1
}

docker cp "$container_name:/etc/ssl/certs/ca-certificates.crt" "$scratch/ca-certificates.crt" >/dev/null
grep -aq 'BEGIN CERTIFICATE' "$scratch/ca-certificates.crt" || {
    printf 'Distroless CA certificate bundle is missing or empty\n' >&2
    exit 1
}

printf 'Runtime smoke passed: platform=%s user=%s liveness=UP ca-bundle=present\n' "$platform" "$container_user"