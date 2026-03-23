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

# ===== Create auto-link authentication flow in master realm =====
FLOW_ALIAS="auto-link-first-broker-login"
FLOW_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  "${KEYCLOAK_URL}/admin/realms/master/authentication/flows/${FLOW_ALIAS}")

if [ "$FLOW_EXISTS" != "200" ]; then
  log "Creating auto-link authentication flow in master realm..."
  curl -s -X POST "${KEYCLOAK_URL}/admin/realms/master/authentication/flows" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"alias\":\"${FLOW_ALIAS}\",\"description\":\"Auto-link Google account by email\",\"providerId\":\"basic-flow\",\"topLevel\":true,\"builtIn\":false}" > /dev/null

  # Add executions: idp-detect-existing-broker-user (REQUIRED) + idp-auto-link (REQUIRED)
  curl -s -X POST "${KEYCLOAK_URL}/admin/realms/master/authentication/flows/${FLOW_ALIAS}/executions/execution" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"provider\":\"idp-detect-existing-broker-user\"}" > /dev/null

  curl -s -X POST "${KEYCLOAK_URL}/admin/realms/master/authentication/flows/${FLOW_ALIAS}/executions/execution" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"provider\":\"idp-auto-link\"}" > /dev/null

  # Set both executions to REQUIRED
  EXECUTIONS=$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "${KEYCLOAK_URL}/admin/realms/master/authentication/flows/${FLOW_ALIAS}/executions")

  # Update each execution requirement via the flow executions endpoint
  UPDATED=$(echo "$EXECUTIONS" | jq '[.[] | .requirement = "REQUIRED"]')

  echo "$UPDATED" | jq -c '.[]' | while read -r exec; do
    EXEC_ID=$(echo "$exec" | jq -r '.id')
    PROVIDER=$(echo "$exec" | jq -r '.providerId')
    RESP=$(curl -s -w "\n%{http_code}" -X PUT \
      "${KEYCLOAK_URL}/admin/realms/master/authentication/flows/${FLOW_ALIAS}/executions" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "$exec")
    CODE=$(echo "$RESP" | tail -1)
    log "Set ${PROVIDER} to REQUIRED: HTTP ${CODE}"
  done

  log "Auto-link flow created in master realm"
else
  log "Auto-link flow already exists in master realm"
fi

# ===== Create/update Google IDP in master realm =====
IDP_JSON="{\"alias\":\"google\",\"displayName\":\"Google\",\"providerId\":\"google\",\"enabled\":true,\"trustEmail\":true,\"storeToken\":false,\"addReadTokenRoleOnCreate\":false,\"firstBrokerLoginFlowAlias\":\"${FLOW_ALIAS}\",\"config\":{\"clientId\":\"${GOOGLE_CLIENT_ID}\",\"clientSecret\":\"${GOOGLE_CLIENT_SECRET}\",\"defaultScope\":\"openid email profile\",\"syncMode\":\"FORCE\"}}"

RESPONSE=$(curl -s -w "\n%{http_code}" -X "$METHOD" "$URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$IDP_JSON")

CODE=$(echo "$RESPONSE" | tail -1)

case "$CODE" in
  200|201|204) log "Google IDP configured in master realm!" ;;
  *)           warn "Failed (HTTP $CODE): $(echo "$RESPONSE" | head -n -1)" ;;
esac

# ===== Ensure admin users exist with admin role in both realms =====
# Pre-creates users so that on first Google login, Keycloak links the
# Google identity to the existing account (trustEmail=true).

ensure_admin_user() {
  local realm=$1
  local email=$2

  log "Ensuring admin user ${email} in realm ${realm}..."

  # Check if user exists
  USER_ID=$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "${KEYCLOAK_URL}/admin/realms/${realm}/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

  # Create user if not found
  if [ -z "$USER_ID" ]; then
    log "Creating user ${email} in ${realm}..."
    local username=$(echo "$email" | cut -d@ -f1)
    curl -s -w "\n" -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/users" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"${email}\",\"email\":\"${email}\",\"enabled\":true,\"emailVerified\":true,\"firstName\":\"${username}\"}" > /dev/null

    USER_ID=$(curl -sf -H "Authorization: Bearer $TOKEN" \
      "${KEYCLOAK_URL}/admin/realms/${realm}/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$USER_ID" ]; then
      warn "Failed to create user ${email} in ${realm}"
      return 1
    fi
    log "User ${email} created in ${realm} (id: ${USER_ID})"
  fi

  # Check if already has admin role
  HAS_ADMIN=$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "${KEYCLOAK_URL}/admin/realms/${realm}/users/${USER_ID}/role-mappings/realm" | jq -r '[.[].name] | index("admin") // empty')

  if [ -n "$HAS_ADMIN" ]; then
    log "User ${email} already has admin role in ${realm}"
    return 0
  fi

  # Get admin role and assign it
  ADMIN_ROLE=$(curl -sf -H "Authorization: Bearer $TOKEN" \
    "${KEYCLOAK_URL}/admin/realms/${realm}/roles/admin")

  if [ -z "$ADMIN_ROLE" ] || [ "$ADMIN_ROLE" = "null" ]; then
    warn "Admin role not found in ${realm}"
    return 1
  fi

  curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${realm}/users/${USER_ID}/role-mappings/realm" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "[${ADMIN_ROLE}]" > /dev/null

  log "Admin role assigned to ${email} in ${realm}!"
}

if [ -n "${ADMIN_EMAILS}" ]; then
  echo "${ADMIN_EMAILS}" | tr ',' '\n' | while read -r email; do
    email=$(echo "$email" | xargs)
    [ -z "$email" ] && continue
    ensure_admin_user "master" "$email"
    ensure_admin_user "calcifer" "$email"
  done
fi

log "Done"

