#!/bin/sh
# =============================================================================
# RabbitMQ User Initialization Script
# =============================================================================
# This script reads users from a CSV file and creates them via the
# RabbitMQ Management API. Passwords are read from environment variables.
# =============================================================================

set -e

RABBITMQ_HOST="${RABBITMQ_HOST:-rabbitmq}"
RABBITMQ_API="http://${RABBITMQ_HOST}:15672/api"
RABBITMQ_ADMIN_USER="${RABBITMQ_ADMIN_USER:-admin}"
RABBITMQ_ADMIN_PASS="${RABBITMQ_ADMIN_PASS:-password_admin}"
USERS_CSV="${USERS_CSV:-/rabbitmq-users.csv}"

# Wait for RabbitMQ Management API to be ready
echo "Waiting for RabbitMQ Management API..."
until curl -s -u "${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASS}" "${RABBITMQ_API}/overview" > /dev/null 2>&1; do
    echo "  ...waiting"
    sleep 3
done
echo "RabbitMQ Management API is ready!"

# =============================================================================
# URL encode function (for vhost encoding)
# =============================================================================
url_encode() {
    echo "$1" | sed 's|/|%2F|g'
}

# =============================================================================
# JSON escape function (for special characters in regex)
# =============================================================================
json_escape() {
    echo "$1" | sed 's/\\/\\\\/g' | sed 's/"/\\"/g'
}

# =============================================================================
# Create user from CSV line
# =============================================================================
create_user() {
    local username="$1"
    local password_env="$2"
    local tags="$3"
    local vhost="$4"
    local configure="$5"
    local write="$6"
    local read="$7"
    local topic_exchange="$8"
    local topic_write="$9"
    local topic_read="${10}"

    # Get password from environment variable
    local password=$(eval echo "\$$password_env")

    if [ -z "$password" ]; then
        echo "WARNING: Password env var '$password_env' not set, skipping user '$username'"
        return 1
    fi

    echo "Creating user: $username"

    # Create or update user
    curl -s -u "${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASS}" \
        -X PUT "${RABBITMQ_API}/users/${username}" \
        -H "Content-Type: application/json" \
        -d "{\"password\":\"${password}\",\"tags\":\"${tags}\"}"
    echo "  User created (tags: ${tags:-none})"

    # Set permissions
    local vhost_encoded=$(url_encode "$vhost")
    local configure_escaped=$(json_escape "$configure")
    local write_escaped=$(json_escape "$write")
    local read_escaped=$(json_escape "$read")
    curl -s -u "${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASS}" \
        -X PUT "${RABBITMQ_API}/permissions/${vhost_encoded}/${username}" \
        -H "Content-Type: application/json" \
        -d "{\"configure\":\"${configure_escaped}\",\"write\":\"${write_escaped}\",\"read\":\"${read_escaped}\"}"
    echo "  Permissions set (vhost: $vhost)"

    # Set topic permissions if exchange is specified
    if [ -n "$topic_exchange" ]; then
        local topic_write_escaped=$(json_escape "$topic_write")
        local topic_read_escaped=$(json_escape "$topic_read")
        curl -s -u "${RABBITMQ_ADMIN_USER}:${RABBITMQ_ADMIN_PASS}" \
            -X PUT "${RABBITMQ_API}/topic-permissions/${vhost_encoded}/${username}" \
            -H "Content-Type: application/json" \
            -d "{\"exchange\":\"${topic_exchange}\",\"write\":\"${topic_write_escaped}\",\"read\":\"${topic_read_escaped}\"}"
        echo "  Topic permissions set (exchange: $topic_exchange)"
    fi

    echo "  User '$username' configured successfully!"
}

# =============================================================================
# Process CSV file
# =============================================================================
if [ ! -f "$USERS_CSV" ]; then
    echo "ERROR: Users CSV file not found: $USERS_CSV"
    exit 1
fi

echo "Processing users from: $USERS_CSV"
echo "============================================="

user_count=0
while IFS=',' read -r username password_env tags vhost configure write read topic_exchange topic_write topic_read; do
    # Skip comments and empty lines
    case "$username" in
        \#*|"") continue ;;
    esac

    create_user "$username" "$password_env" "$tags" "$vhost" "$configure" "$write" "$read" "$topic_exchange" "$topic_write" "$topic_read"
    user_count=$((user_count + 1))
    echo ""
done < "$USERS_CSV"

echo "============================================="
echo "RabbitMQ initialization complete! ($user_count users processed)"

