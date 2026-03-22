#!/bin/bash
# =============================================================================
# Keycloak Init Script
# Configures realm, users, and identity providers via Admin REST API
#
# This script:
# 1. Configures Google IDP in MASTER realm for server admin access
# 2. Creates admin user from ADMIN_EMAILS with master realm admin role
# 3. Disables the temporary bootstrap admin account
# 4. Configures Google IDP in calcifer realm for application access
# 5. Configures API client for M2M (machine-to-machine) access
# =============================================================================

set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-calcifer}"
API_CLIENT_ID="${API_CLIENT_ID:-calcifer-api}"
API_CLIENT_SECRET="${API_CLIENT_SECRET:-}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[INIT]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Wait for Keycloak to be ready
wait_for_keycloak() {
    log "Waiting for Keycloak to be ready..."
    # Health check is on port 9000, API is on port 8080
    local health_url="${KEYCLOAK_URL%:8080}:9000/health/ready"
    for i in {1..60}; do
        if curl -sf "${health_url}" > /dev/null 2>&1; then
            log "Keycloak is ready!"
            return 0
        fi
        sleep 5
    done
    error "Keycloak not ready after 5 minutes"
    exit 1
}

# Get admin token
get_token() {
    curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "username=${KEYCLOAK_ADMIN}" \
        -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
        -d "grant_type=password" \
        -d "client_id=admin-cli" | jq -r '.access_token'
}

# Check if realm exists
realm_exists() {
    local token=$1
    curl -sf -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}" | grep -q "200"
}

# Create realm if not exists
create_realm() {
    local token=$1
    log "Creating realm ${REALM}..."
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "{\"realm\": \"${REALM}\", \"enabled\": true}"
}

# Configure Google Identity Provider in a realm
configure_google_idp_for_realm() {
    local token=$1
    local target_realm=$2

    if [ -z "${GOOGLE_CLIENT_ID}" ] || [ -z "${GOOGLE_CLIENT_SECRET}" ]; then
        warn "GOOGLE_CLIENT_ID or GOOGLE_CLIENT_SECRET not set, skipping Google IDP"
        return 0
    fi

    log "Configuring Google IDP in realm: ${target_realm}..."

    # Check if IDP exists
    local exists=$(curl -sf -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google")

    local idp_config=$(cat <<EOF
{
    "alias": "google",
    "displayName": "Google",
    "providerId": "google",
    "enabled": true,
    "trustEmail": true,
    "firstBrokerLoginFlowAlias": "first broker login",
    "config": {
        "clientId": "${GOOGLE_CLIENT_ID}",
        "clientSecret": "${GOOGLE_CLIENT_SECRET}",
        "defaultScope": "openid email profile",
        "syncMode": "FORCE"
    }
}
EOF
)

    if [ "$exists" = "200" ]; then
        log "Updating existing Google IDP in ${target_realm}..."
        curl -sf -X PUT "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${idp_config}" || true
    else
        log "Creating Google IDP in ${target_realm}..."
        curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${idp_config}"

        # Add mappers for Google IDP
        configure_google_mappers "$token" "$target_realm"
    fi
}

# Configure Google IDP Mappers
configure_google_mappers() {
    local token=$1
    local target_realm=$2
    log "Configuring Google IDP mappers in ${target_realm}..."

    # Username from email
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "username-from-email",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-username-idp-mapper",
            "config": {"syncMode": "FORCE", "template": "${CLAIM.email}"}
        }' || true

    # First name
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "first-name",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-user-attribute-idp-mapper",
            "config": {"syncMode": "FORCE", "claim": "given_name", "user.attribute": "firstName"}
        }' || true

    # Last name
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "last-name",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-user-attribute-idp-mapper",
            "config": {"syncMode": "FORCE", "claim": "family_name", "user.attribute": "lastName"}
        }' || true
}

# Configure MASTER realm admin user from ADMIN_EMAILS
# This gives full server admin access via Google login
configure_master_admin() {
    local token=$1

    if [ -z "${ADMIN_EMAILS}" ]; then
        warn "ADMIN_EMAILS not set, skipping master admin configuration"
        return 0
    fi

    # Get first admin email
    local admin_email=$(echo "${ADMIN_EMAILS}" | cut -d',' -f1 | xargs)
    log "Configuring master admin: ${admin_email}"

    # Check if user exists in master realm
    local user_id=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/master/users?email=${admin_email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$user_id" ]; then
        log "Master admin user ${admin_email} will be created on first Google login to master realm"
        log "After first login, re-run this init to assign admin role"
        return 0
    fi

    log "User ${admin_email} exists in master realm (id: ${user_id})"

    # Get master realm admin role
    local admin_role=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/master/roles/admin")

    local admin_role_id=$(echo "$admin_role" | jq -r '.id')

    if [ -z "$admin_role_id" ] || [ "$admin_role_id" = "null" ]; then
        warn "Master admin role not found"
        return 0
    fi

    # Assign admin role
    log "Assigning master admin role to ${admin_email}..."
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/master/users/${user_id}/role-mappings/realm" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "[{\"id\": \"${admin_role_id}\", \"name\": \"admin\"}]" || true

    log "Master admin role assigned to ${admin_email}"
}

# Disable the temporary bootstrap admin account
disable_bootstrap_admin() {
    local token=$1

    log "Checking for bootstrap admin account..."

    # Get bootstrap admin user
    local admin_user=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/master/users?username=${KEYCLOAK_ADMIN}&exact=true" | jq -r '.[0]')

    local admin_id=$(echo "$admin_user" | jq -r '.id // empty')

    if [ -z "$admin_id" ]; then
        log "Bootstrap admin not found (already removed or renamed)"
        return 0
    fi

    # Check if this is a temporary admin (email not set or same as username)
    local admin_email=$(echo "$admin_user" | jq -r '.email // empty')

    # Only disable if admin email is in ADMIN_EMAILS (meaning permanent admin exists)
    if [ -z "${ADMIN_EMAILS}" ]; then
        warn "Cannot disable bootstrap admin: no ADMIN_EMAILS configured"
        return 0
    fi

    # Check if permanent admin has logged in
    local first_admin_email=$(echo "${ADMIN_EMAILS}" | cut -d',' -f1 | xargs)
    local permanent_admin=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/master/users?email=${first_admin_email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$permanent_admin" ]; then
        warn "Cannot disable bootstrap admin: permanent admin ${first_admin_email} hasn't logged in yet"
        warn "Login to Keycloak master realm with Google first, then re-run init"
        return 0
    fi

    log "Permanent admin exists. Disabling bootstrap admin account..."

    # Disable the bootstrap admin
    curl -sf -X PUT "${KEYCLOAK_URL}/admin/realms/master/users/${admin_id}" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{"enabled": false}' || true

    log "Bootstrap admin account disabled!"
}

# Configure admin users in calcifer realm
configure_realm_admins() {
    local token=$1

    if [ -z "${ADMIN_EMAILS}" ]; then
        warn "ADMIN_EMAILS not set, skipping realm admin configuration"
        return 0
    fi

    log "Configuring realm admins: ${ADMIN_EMAILS}"

    # Get admin role ID for calcifer realm
    local admin_role_id=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/roles/admin" | jq -r '.id')

    if [ -z "$admin_role_id" ] || [ "$admin_role_id" = "null" ]; then
        warn "Admin role not found in ${REALM} realm, skipping"
        return 0
    fi

    # Process each email
    IFS=',' read -ra EMAILS <<< "$ADMIN_EMAILS"
    for email in "${EMAILS[@]}"; do
        email=$(echo "$email" | xargs)

        local user_id=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

        if [ -n "$user_id" ]; then
            log "Assigning admin role to ${email} in ${REALM} realm..."
            curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/role-mappings/realm" \
                -H "Authorization: Bearer ${token}" \
                -H "Content-Type: application/json" \
                -d "[{\"id\": \"${admin_role_id}\", \"name\": \"admin\"}]" || true
        else
            log "User ${email} not found in ${REALM} realm (will be created on first login)"
        fi
    done
}

# Configure API client for M2M access
configure_api_client() {
    local token=$1

    if [ -z "${API_CLIENT_SECRET}" ]; then
        warn "API_CLIENT_SECRET not set, skipping API client configuration"
        return 0
    fi

    log "Configuring API client: ${API_CLIENT_ID}"

    # Get client ID
    local client=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${API_CLIENT_ID}")

    local client_uuid=$(echo "$client" | jq -r '.[0].id // empty')

    if [ -z "$client_uuid" ]; then
        log "Creating API client..."
        curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "{
                \"clientId\": \"${API_CLIENT_ID}\",
                \"name\": \"Calcifer API Client\",
                \"description\": \"Service account for programmatic API access (M2M)\",
                \"enabled\": true,
                \"publicClient\": false,
                \"secret\": \"${API_CLIENT_SECRET}\",
                \"standardFlowEnabled\": false,
                \"directAccessGrantsEnabled\": false,
                \"serviceAccountsEnabled\": true,
                \"protocol\": \"openid-connect\"
            }"

        # Get the new client UUID
        client=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${API_CLIENT_ID}")
        client_uuid=$(echo "$client" | jq -r '.[0].id // empty')
    else
        log "Updating API client secret..."
        curl -sf -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "{
                \"clientId\": \"${API_CLIENT_ID}\",
                \"secret\": \"${API_CLIENT_SECRET}\",
                \"serviceAccountsEnabled\": true
            }" || true
    fi

    # Assign admin role to service account
    if [ -n "$client_uuid" ]; then
        log "Configuring service account roles..."

        # Get service account user
        local sa_user=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}/service-account-user")

        local sa_user_id=$(echo "$sa_user" | jq -r '.id // empty')

        if [ -n "$sa_user_id" ]; then
            # Get admin role
            local admin_role=$(curl -sf \
                -H "Authorization: Bearer ${token}" \
                "${KEYCLOAK_URL}/admin/realms/${REALM}/roles/admin")

            local admin_role_id=$(echo "$admin_role" | jq -r '.id // empty')

            if [ -n "$admin_role_id" ]; then
                log "Assigning admin role to API service account..."
                curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${sa_user_id}/role-mappings/realm" \
                    -H "Authorization: Bearer ${token}" \
                    -H "Content-Type: application/json" \
                    -d "[{\"id\": \"${admin_role_id}\", \"name\": \"admin\"}]" || true
            fi
        fi
    fi

    log "API client configured!"
}

# Main
main() {
    wait_for_keycloak

    log "Getting admin token..."
    TOKEN=$(get_token)

    if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
        error "Failed to get admin token"
        exit 1
    fi

    # ===== MASTER REALM CONFIGURATION =====
    log "========== MASTER REALM SETUP =========="

    # Configure Google IDP in master realm for admin access
    configure_google_idp_for_realm "$TOKEN" "master"

    # Configure permanent admin user
    configure_master_admin "$TOKEN"

    # Disable bootstrap admin (only if permanent admin exists)
    disable_bootstrap_admin "$TOKEN"

    # ===== CALCIFER REALM CONFIGURATION =====
    log "========== CALCIFER REALM SETUP =========="

    if ! realm_exists "$TOKEN"; then
        create_realm "$TOKEN"
    else
        log "Realm ${REALM} already exists"
    fi

    # Configure Google IDP in calcifer realm for app access
    configure_google_idp_for_realm "$TOKEN" "${REALM}"

    # Configure realm admins
    configure_realm_admins "$TOKEN"

    # Configure API client for M2M access
    configure_api_client "$TOKEN"

    log "=========================================="
    log "Keycloak configuration complete!"
    log ""
    log "IMPORTANT: To complete admin setup:"
    log "1. Go to https://keycloak.dmgiangi.dev/admin/master/console/"
    log "2. Click 'Sign in with Google' and login with: ${ADMIN_EMAILS}"
    log "3. Re-run this init container to assign admin role"
    log ""
    if [ -n "${API_CLIENT_SECRET}" ]; then
        log "API Access configured! Use:"
        log "  curl -X POST https://keycloak.dmgiangi.dev/realms/calcifer/protocol/openid-connect/token \\"
        log "    -d 'grant_type=client_credentials' \\"
        log "    -d 'client_id=${API_CLIENT_ID}' \\"
        log "    -d 'client_secret=\${API_CLIENT_SECRET}'"
        log ""
    fi
}

main "$@"

