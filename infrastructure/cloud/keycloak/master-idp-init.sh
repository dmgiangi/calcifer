#!/bin/sh
# Adds Google IDP to the master realm (for admin console login).
# Runs once after Keycloak is healthy. Safe to re-run (idempotent).

set -e

apk add --no-cache curl jq > /dev/null 2>&1

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KC_ADMIN="${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}"
KC_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}"

log()  { echo "[MASTER-IDP] $*"; }
warn() { echo "[MASTER-IDP] WARN: $*"; }

# Wait for Keycloak
log "Waiting for Keycloak..."
until curl -sf "${KEYCLOAK_URL}/health/ready" > /dev/null 2>&1; do sleep 3; done
log "Keycloak is ready"

# Get admin token
TOKEN=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=${KC_ADMIN}" \
  -d "password=${KC_PASS}" | jq -r '.access_token // empty')

if [ -z "$TOKEN" ]; then
  warn "Failed to get admin token"
  exit 1
fi

# Check if Google IDP already exists
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  "${KEYCLOAK_URL}/admin/realms/master/identity-provider/instances/google")

if [ "$HTTP_CODE" = "200" ]; then
  log "Google IDP already exists in master realm - updating..."
  METHOD="PUT"
  URL="${KEYCLOAK_URL}/admin/realms/master/identity-provider/instances/google"
else
  log "Creating Google IDP in master realm..."
  METHOD="POST"
  URL="${KEYCLOAK_URL}/admin/realms/master/identity-provider/instances"
fi

IDP_JSON="{\"alias\":\"google\",\"displayName\":\"Google\",\"providerId\":\"google\",\"enabled\":true,\"trustEmail\":true,\"storeToken\":false,\"addReadTokenRoleOnCreate\":false,\"firstBrokerLoginFlowAlias\":\"first broker login\",\"config\":{\"clientId\":\"${GOOGLE_CLIENT_ID}\",\"clientSecret\":\"${GOOGLE_CLIENT_SECRET}\",\"defaultScope\":\"openid email profile\",\"syncMode\":\"INHERIT\"}}"

RESPONSE=$(curl -s -w "\n%{http_code}" -X "$METHOD" "$URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$IDP_JSON")

CODE=$(echo "$RESPONSE" | tail -1)

case "$CODE" in
  200|201|204) log "Google IDP configured in master realm!" ;;
  *)           warn "Failed (HTTP $CODE): $(echo "$RESPONSE" | head -n -1)" ;;
esac

log "Done"

