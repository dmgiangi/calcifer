#!/bin/sh
# Post-startup Keycloak init (idempotent, safe to re-run):
# 1. Adds Google IDP to master realm (for admin console login)
# 2. Assigns admin role to ADMIN_EMAILS users in master + calcifer realms

set -e

apk add --no-cache curl jq > /dev/null 2>&1

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KC_ADMIN="${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}"
KC_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}"

log()  { echo "[MASTER-IDP] $*"; }
warn() { echo "[MASTER-IDP] WARN: $*"; }

# Wait for Keycloak (depends_on healthy should handle this, but just in case)
log "Checking Keycloak readiness..."
RETRIES=0
until curl -sf "${KEYCLOAK_URL}/realms/master" > /dev/null 2>&1; do
  RETRIES=$((RETRIES + 1))
  if [ "$RETRIES" -ge 60 ]; then
    warn "Keycloak not ready after 3 minutes, giving up"
    exit 1
  fi
  sleep 3
done
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

# ===== Assign admin role to ADMIN_EMAILS users in master realm =====
if [ -n "${ADMIN_EMAILS}" ]; then
  # Process each email (comma-separated)
  echo "${ADMIN_EMAILS}" | tr ',' '\n' | while read -r email; do
    email=$(echo "$email" | xargs)  # trim whitespace
    [ -z "$email" ] && continue

    log "Checking master realm user: ${email}"

    # Find user by email
    USER_ID=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/master/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$USER_ID" ]; then
      log "User ${email} not found in master realm (will be created on first Google login)"
      continue
    fi

    # Check if already has admin role
    HAS_ADMIN=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/master/users/${USER_ID}/role-mappings/realm" | jq -r '[.[].name] | index("admin") // empty')

    if [ -n "$HAS_ADMIN" ]; then
      log "User ${email} already has admin role in master realm"
      continue
    fi

    # Get admin role definition
    ADMIN_ROLE=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/master/roles/admin")

    # Assign admin role
    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/master/users/${USER_ID}/role-mappings/realm" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "[${ADMIN_ROLE}]" > /dev/null

    log "Admin role assigned to ${email} in master realm!"
  done
fi

# ===== Assign admin role to ADMIN_EMAILS users in calcifer realm =====
if [ -n "${ADMIN_EMAILS}" ]; then
  echo "${ADMIN_EMAILS}" | tr ',' '\n' | while read -r email; do
    email=$(echo "$email" | xargs)
    [ -z "$email" ] && continue

    log "Checking calcifer realm user: ${email}"

    USER_ID=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/calcifer/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$USER_ID" ]; then
      log "User ${email} not found in calcifer realm (will be created on first Google login)"
      continue
    fi

    HAS_ADMIN=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/calcifer/users/${USER_ID}/role-mappings/realm" | jq -r '[.[].name] | index("admin") // empty')

    if [ -n "$HAS_ADMIN" ]; then
      log "User ${email} already has admin role in calcifer realm"
      continue
    fi

    ADMIN_ROLE=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/calcifer/roles/admin")

    curl -s -X POST "${KEYCLOAK_URL}/admin/realms/calcifer/users/${USER_ID}/role-mappings/realm" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "[${ADMIN_ROLE}]" > /dev/null

    log "Admin role assigned to ${email} in calcifer realm!"
  done
fi

log "Done"

