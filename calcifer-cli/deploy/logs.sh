#!/usr/bin/env bash
# =============================================================================
# Logs Command - Fetch logs from services
# =============================================================================

CURRENT_CMD="logs"

cmd_logs() {
    local service=""
    local lines=50
    local follow=false
    local target="remote"
    local since=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --lines|-n)
                lines="$2"
                shift 2
                ;;
            --follow|-f)
                follow=true
                shift
                ;;
            --local)
                target="local"
                shift
                ;;
            --since)
                since="$2"
                shift 2
                ;;
            -*)
                shift
                ;;
            *)
                service="$1"
                shift
                ;;
        esac
    done

    log_progress "Fetching logs (service: ${service:-all}, lines: ${lines}, target: ${target})..."

    # Map service names to container names
    declare -A service_map=(
        ["core-server"]="calcifer_core_server"
        ["grafana"]="calcifer_grafana"
        ["prometheus"]="calcifer_prometheus"
        ["rabbitmq"]="calcifer_rabbitmq"
        ["redis"]="calcifer_redis"
        ["mongodb"]="calcifer_mongodb"
        ["loki"]="calcifer_loki"
        ["tempo"]="calcifer_tempo"
        ["keycloak"]="calcifer_keycloak"
        ["traefik"]="calcifer_traefik"
    )

    local docker_cmd="docker logs"
    local compose_cmd="docker compose -f docker-compose.yaml logs"

    if [[ -n "${since}" ]]; then
        docker_cmd="${docker_cmd} --since ${since}"
        compose_cmd="${compose_cmd} --since ${since}"
    fi

    docker_cmd="${docker_cmd} --tail ${lines}"
    compose_cmd="${compose_cmd} --tail ${lines}"

    local logs_output=""
    local fetch_success=true

    if [[ "${target}" == "remote" ]]; then
        if ! ssh_check; then
            json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH connectivity"
            return 1
        fi

        if [[ -n "${service}" ]]; then
            local container="${service_map[${service}]:-${service}}"
            logs_output=$(ssh_exec "${docker_cmd} ${container} 2>&1" || echo "ERROR: Failed to fetch logs")
        else
            logs_output=$(ssh_exec "cd ${REMOTE_DEPLOY_DIR}/infrastructure && ${compose_cmd} 2>&1" || echo "ERROR: Failed to fetch logs")
        fi
    else
        if [[ -n "${service}" ]]; then
            local container="${service_map[${service}]:-${service}}"
            logs_output=$(${docker_cmd} "${container}" 2>&1 || echo "ERROR: Failed to fetch logs")
        else
            logs_output=$((cd infrastructure && ${compose_cmd}) 2>&1 || echo "ERROR: Failed to fetch logs")
        fi
    fi

    # Check for errors in output
    if [[ "${logs_output}" == ERROR:* ]]; then
        fetch_success=false
    fi

    # Escape logs for JSON (handle newlines and quotes)
    local escaped_logs
    escaped_logs=$(echo "${logs_output}" | head -n "${lines}" | jq -Rs '.')

    local data
    data=$(cat << EOF
{
    "service": "${service:-all}",
    "target": "${target}",
    "lines_requested": ${lines},
    "logs": ${escaped_logs}
}
EOF
)

    if [[ "${fetch_success}" == "true" ]]; then
        json_success "logs" "${data}" '["./deploy status", "./deploy test"]'
    else
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "logs",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "Failed to fetch logs"}],
  "next_actions": ["./deploy status"]
}
EOF
        return 1
    fi
}

