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

# Don't use set -e - we handle errors manually for better logging
# set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-calcifer}"
KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-calcifer-gateway}"
KEYCLOAK_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"
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

# Get admin token - tries multiple methods
get_token() {
    local token=""

    # Method 1: Try bootstrap admin (password grant)
    log "Trying bootstrap admin authentication..."
    token=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "username=${KEYCLOAK_ADMIN}" \
        -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
        -d "grant_type=password" \
        -d "client_id=admin-cli" 2>/dev/null | jq -r '.access_token // empty')

    if [ -n "$token" ] && [ "$token" != "null" ]; then
        log "Authenticated via bootstrap admin"
        echo "$token"
        return 0
    fi

    # Method 2: Try init service account (client credentials)
    if [ -n "${INIT_CLIENT_SECRET}" ]; then
        log "Trying init service account..."
        token=$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
            -H "Content-Type: application/x-www-form-urlencoded" \
            -d "client_id=calcifer-init" \
            -d "client_secret=${INIT_CLIENT_SECRET}" \
            -d "grant_type=client_credentials" 2>/dev/null | jq -r '.access_token // empty')

        if [ -n "$token" ] && [ "$token" != "null" ]; then
            log "Authenticated via init service account"
            echo "$token"
            return 0
        fi
    fi

    # No authentication method worked
    echo ""
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

    local response=$(curl -s -w "\n%{http_code}" -X POST "${KEYCLOAK_URL}/admin/realms" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "{\"realm\": \"${REALM}\", \"enabled\": true, \"registrationAllowed\": false, \"loginWithEmailAllowed\": true}")

    local http_code=$(echo "$response" | tail -1)
    local body=$(echo "$response" | head -n -1)

    if [ "$http_code" = "201" ]; then
        log "Realm ${REALM} created successfully"
    else
        warn "Failed to create realm (HTTP $http_code): $body"
    fi
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

    # Build JSON without heredoc to avoid escaping issues
    local idp_config="{\"alias\":\"google\",\"displayName\":\"Google\",\"providerId\":\"google\",\"enabled\":true,\"trustEmail\":true,\"firstBrokerLoginFlowAlias\":\"first broker login\",\"config\":{\"clientId\":\"${GOOGLE_CLIENT_ID}\",\"clientSecret\":\"${GOOGLE_CLIENT_SECRET}\",\"defaultScope\":\"openid email profile\",\"syncMode\":\"FORCE\"}}"

    if [ "$exists" = "200" ]; then
        log "Updating existing Google IDP in ${target_realm}..."
        curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances/google" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${idp_config}" > /dev/null || warn "Failed to update Google IDP"
    else
        log "Creating Google IDP in ${target_realm}..."
        local response=$(curl -s -w "\n%{http_code}" -X POST "${KEYCLOAK_URL}/admin/realms/${target_realm}/identity-provider/instances" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${idp_config}")

        local http_code=$(echo "$response" | tail -1)
        local body=$(echo "$response" | head -n -1)

        if [ "$http_code" = "201" ] || [ "$http_code" = "204" ]; then
            log "Google IDP created successfully"
            # Add mappers for Google IDP
            configure_google_mappers "$token" "$target_realm"
        else
            warn "Failed to create Google IDP (HTTP $http_code): $body"
        fi
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

# Configure gateway client (for forward-auth)
configure_gateway_client() {
    local token=$1

    if [ -z "${KEYCLOAK_CLIENT_SECRET}" ]; then
        warn "KEYCLOAK_CLIENT_SECRET not set, skipping gateway client configuration"
        return 0
    fi

    log "Configuring gateway client: ${KEYCLOAK_CLIENT_ID}"

    # Get client UUID
    local client=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${KEYCLOAK_CLIENT_ID}")

    local client_uuid=$(echo "$client" | jq -r '.[0].id // empty')

    if [ -z "$client_uuid" ]; then
        log "Creating gateway client..."
        local create_json="{\"clientId\":\"${KEYCLOAK_CLIENT_ID}\",\"name\":\"Calcifer Gateway\",\"enabled\":true,\"publicClient\":false,\"redirectUris\":[\"https://*.dmgiangi.dev/*\",\"https://auth.dmgiangi.dev/_oauth\"],\"webOrigins\":[\"https://*.dmgiangi.dev\"],\"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":true,\"protocol\":\"openid-connect\"}"

        local response=$(curl -s -w "\n%{http_code}" -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${create_json}")

        local http_code=$(echo "$response" | tail -1)
        local body=$(echo "$response" | head -n -1)

        if [ "$http_code" != "201" ]; then
            warn "Failed to create gateway client (HTTP $http_code): $body"
        fi

        # Get the newly created client UUID
        client=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${KEYCLOAK_CLIENT_ID}")
        client_uuid=$(echo "$client" | jq -r '.[0].id // empty')
    fi

    # Set the client secret explicitly
    if [ -n "$client_uuid" ]; then
        log "Setting gateway client secret..."

        # Update the client with the correct secret value
        local update_json="{\"id\":\"${client_uuid}\",\"clientId\":\"${KEYCLOAK_CLIENT_ID}\",\"secret\":\"${KEYCLOAK_CLIENT_SECRET}\",\"enabled\":true,\"publicClient\":false,\"clientAuthenticatorType\":\"client-secret\",\"redirectUris\":[\"https://*.dmgiangi.dev/*\",\"https://auth.dmgiangi.dev/_oauth\"],\"webOrigins\":[\"https://*.dmgiangi.dev\"],\"standardFlowEnabled\":true,\"directAccessGrantsEnabled\":true}"

        curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${update_json}" > /dev/null || warn "Failed to update gateway client"

        log "Gateway client configured!"
    else
        warn "Could not find or create gateway client"
    fi
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
        local create_json="{\"clientId\":\"${API_CLIENT_ID}\",\"name\":\"Calcifer API Client\",\"description\":\"Service account for programmatic API access (M2M)\",\"enabled\":true,\"publicClient\":false,\"secret\":\"${API_CLIENT_SECRET}\",\"standardFlowEnabled\":false,\"directAccessGrantsEnabled\":false,\"serviceAccountsEnabled\":true,\"protocol\":\"openid-connect\"}"

        curl -s -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/clients" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${create_json}" > /dev/null

        # Get the new client UUID
        client=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/clients?clientId=${API_CLIENT_ID}")
        client_uuid=$(echo "$client" | jq -r '.[0].id // empty')
    else
        log "Updating API client secret..."
        local update_json="{\"clientId\":\"${API_CLIENT_ID}\",\"secret\":\"${API_CLIENT_SECRET}\",\"serviceAccountsEnabled\":true}"

        curl -s -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/clients/${client_uuid}" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "${update_json}" > /dev/null || true
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
        error "Failed to get admin token!"
        echo ""
        echo "╔════════════════════════════════════════════════════════════════╗"
        echo "║  AUTHENTICATION FAILED - Manual action required                ║"
        echo "╚════════════════════════════════════════════════════════════════╝"
        echo ""
        echo "The bootstrap admin is disabled and no service account is configured."
        echo ""
        echo "To fix this:"
        echo "  1. Login to Keycloak admin console with Google:"
        echo "     https://keycloak.dmgiangi.dev/admin/master/console/"
        echo ""
        echo "  2. Create a service account for init:"
        echo "     - Go to: Clients → Create client"
        echo "     - Client ID: calcifer-init"
        echo "     - Enable: Client authentication, Service account roles"
        echo "     - Save, then go to Credentials tab and copy the secret"
        echo ""
        echo "  3. Assign admin role to service account:"
        echo "     - Go to: Clients → calcifer-init → Service account roles"
        echo "     - Assign role → Filter by clients → admin-cli"
        echo "     - Select: admin"
        echo ""
        echo "  4. Add INIT_CLIENT_SECRET to .env and re-run init"
        echo ""
        exit 1
    fi

    # ===== MASTER REALM =====
    # Keep it simple - just use bootstrap admin for Keycloak management
    # No Google IDP needed in master realm
    log "========== MASTER REALM =========="
    log "Master realm uses bootstrap admin (password-based)"
    log "For Keycloak admin access: https://keycloak.dmgiangi.dev/admin/master/console/"
    log "Use admin / \${KEYCLOAK_ADMIN_PASSWORD}"

    # ===== CALCIFER REALM CONFIGURATION =====
    # This is where all app authentication happens
    log "========== CALCIFER REALM SETUP =========="

    if ! realm_exists "$TOKEN"; then
        create_realm "$TOKEN"
    else
        log "Realm ${REALM} already exists"
    fi

    # Configure Google IDP in calcifer realm for app access (users)
    configure_google_idp_for_realm "$TOKEN" "${REALM}"

    # Configure gateway client (for forward-auth)
    configure_gateway_client "$TOKEN"

    # Configure realm admins
    configure_realm_admins "$TOKEN"

    # Configure API client for M2M access
    configure_api_client "$TOKEN"

    log "=========================================="
    log "Keycloak configuration complete!"
    log ""
    log "ACCESS:"
    log "  Keycloak Admin: https://keycloak.dmgiangi.dev/admin/master/console/"
    log "    - Username: admin"
    log "    - Password: \${KEYCLOAK_ADMIN_PASSWORD} from .env"
    log ""
    log "  App Login (Google): Sign in via any protected service"
    log "    - Authorized users: ${ADMIN_EMAILS}"
    log ""
    if [ -n "${API_CLIENT_SECRET}" ]; then
        log "  API Access (M2M):"
        log "    curl -X POST https://keycloak.dmgiangi.dev/realms/calcifer/protocol/openid-connect/token \\"
        log "      -d 'grant_type=client_credentials' \\"
        log "      -d 'client_id=${API_CLIENT_ID}' \\"
        log "      -d 'client_secret=\${API_CLIENT_SECRET}'"
        log ""
    fi
}

main "$@"

