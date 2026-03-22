#!/bin/bash
# =============================================================================
# Keycloak Init Script
# Configures realm, users, and identity providers via Admin REST API
# =============================================================================

set -e

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${REALM:-calcifer}"

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

# Configure Google Identity Provider
configure_google_idp() {
    local token=$1

    if [ -z "${GOOGLE_CLIENT_ID}" ] || [ -z "${GOOGLE_CLIENT_SECRET}" ]; then
        warn "GOOGLE_CLIENT_ID or GOOGLE_CLIENT_SECRET not set, skipping Google IDP"
        return 0
    fi

    log "Configuring Google Identity Provider..."

    # Check if IDP exists
    local exists=$(curl -sf -o /dev/null -w "%{http_code}" \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/google")

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
        log "Deleting existing Google IDP to recreate with correct settings..."
        curl -sf -X DELETE "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/google" \
            -H "Authorization: Bearer ${token}"
    fi

    log "Creating Google IDP..."
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d "${idp_config}"

    # Add mappers for Google IDP
    configure_google_mappers "$token"
}

# Configure Google IDP Mappers
configure_google_mappers() {
    local token=$1
    log "Configuring Google IDP mappers..."

    # Username from email
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "username-from-email",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-username-idp-mapper",
            "config": {"syncMode": "FORCE", "template": "${CLAIM.email}"}
        }' || true

    # First name
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "first-name",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-user-attribute-idp-mapper",
            "config": {"syncMode": "FORCE", "claim": "given_name", "user.attribute": "firstName"}
        }' || true

    # Last name
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/google/mappers" \
        -H "Authorization: Bearer ${token}" \
        -H "Content-Type: application/json" \
        -d '{
            "name": "last-name",
            "identityProviderAlias": "google",
            "identityProviderMapper": "oidc-user-attribute-idp-mapper",
            "config": {"syncMode": "FORCE", "claim": "family_name", "user.attribute": "lastName"}
        }' || true
}

# Configure admin users from ADMIN_EMAILS
configure_admin_users() {
    local token=$1

    if [ -z "${ADMIN_EMAILS}" ]; then
        warn "ADMIN_EMAILS not set, skipping admin user configuration"
        return 0
    fi

    log "Configuring admin users: ${ADMIN_EMAILS}"

    # Get admin role ID
    local admin_role_id=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/roles/admin" | jq -r '.id')

    if [ -z "$admin_role_id" ] || [ "$admin_role_id" = "null" ]; then
        warn "Admin role not found, skipping user configuration"
        return 0
    fi

    # Process each email (comma-separated)
    IFS=',' read -ra EMAILS <<< "$ADMIN_EMAILS"
    for email in "${EMAILS[@]}"; do
        email=$(echo "$email" | xargs)  # trim whitespace
        configure_admin_user "$token" "$email" "$admin_role_id"
    done
}

# Configure a single admin user
configure_admin_user() {
    local token=$1
    local email=$2
    local admin_role_id=$3

    log "Configuring admin user: ${email}"

    # Check if user exists
    local user_id=$(curl -sf \
        -H "Authorization: Bearer ${token}" \
        "${KEYCLOAK_URL}/admin/realms/${REALM}/users?email=${email}&exact=true" | jq -r '.[0].id // empty')

    if [ -z "$user_id" ]; then
        log "Creating user ${email}..."
        # Create user
        curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "{
                \"username\": \"${email}\",
                \"email\": \"${email}\",
                \"enabled\": true,
                \"emailVerified\": true
            }"

        # Get the new user ID
        user_id=$(curl -sf \
            -H "Authorization: Bearer ${token}" \
            "${KEYCLOAK_URL}/admin/realms/${REALM}/users?email=${email}&exact=true" | jq -r '.[0].id // empty')
    else
        log "User ${email} already exists"
    fi

    if [ -n "$user_id" ]; then
        # Assign admin role
        log "Assigning admin role to ${email}..."
        curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${user_id}/role-mappings/realm" \
            -H "Authorization: Bearer ${token}" \
            -H "Content-Type: application/json" \
            -d "[{\"id\": \"${admin_role_id}\", \"name\": \"admin\"}]" || true
        log "Admin role assigned to ${email}"
    fi
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
    
    if ! realm_exists "$TOKEN"; then
        create_realm "$TOKEN"
    else
        log "Realm ${REALM} already exists"
    fi
    
    configure_google_idp "$TOKEN"
    configure_admin_users "$TOKEN"
    
    log "Keycloak configuration complete!"
}

main "$@"

