#!/usr/bin/env bash
# =============================================================================
# Test Command - Run health checks and smoke tests
# =============================================================================

CURRENT_CMD="test"

cmd_test() {
    local target="remote"
    local timeout=30
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --local)
                target="local"
                shift
                ;;
            --timeout)
                timeout="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Running health checks (target: ${target}, timeout: ${timeout}s)..."

    local base_url="http://localhost"
    local use_ssh=false

    if [[ "${target}" == "remote" ]]; then
        use_ssh=true
    fi

    # Define health check endpoints based on target environment
    local -a checks=()

    if [[ "${DEPLOY_TARGET}" == "cloud" ]]; then
        checks=(
            "grafana|${base_url}:3000/api/health|Grafana Dashboard"
            "prometheus|${base_url}:9090/-/healthy|Prometheus Metrics"
            "loki|http://calcifer-cloud_loki:3100/ready|Loki Logs"
            "tempo|http://calcifer-cloud_tempo:3200/ready|Tempo Traces"
            "keycloak|http://calcifer_cloud_keycloak:8080/health/ready|Keycloak Identity"
            "traefik|${base_url}:8080/api/overview|Traefik Router"
        )
    else
        checks=(
            "core-server|${base_url}:8080/actuator/health|Core Server API"
            "grafana|${base_url}:3000/api/health|Grafana Dashboard"
            "prometheus|${base_url}:9090/-/healthy|Prometheus Metrics"
            "rabbitmq|${base_url}:15672/api/health/checks/alarms|RabbitMQ Broker"
            "loki|${base_url}:3100/ready|Loki Logs"
            "tempo|${base_url}:3200/ready|Tempo Traces"
        )
    fi

    local results=()
    local all_passed=true
    local passed=0
    local failed=0

    for check in "${checks[@]}"; do
        IFS='|' read -r name url description <<< "${check}"

        log_progress "Checking ${description}..."

        local http_code
        local response_time
        local start
        start=$(date +%s%3N)

        if [[ "${use_ssh}" == "true" ]]; then
            # Execute curl via SSH on remote server (localhost inside server)
            http_code=$(ssh_exec "curl -s -o /dev/null -w %{http_code} --max-time ${timeout} ${url} 2>/dev/null || echo 000")
        else
            http_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time "${timeout}" "${url}" 2>/dev/null || echo "000")
        fi

        # Clean http_code (remove any whitespace)
        http_code=$(echo "${http_code}" | tr -d '[:space:]')

        local end
        end=$(date +%s%3N)
        response_time=$((end - start))
        
        local status="healthy"
        local passed_check=true
        
        if [[ "${http_code}" == "000" ]]; then
            status="unreachable"
            passed_check=false
        elif [[ "${http_code}" -ge 400 ]]; then
            status="unhealthy"
            passed_check=false
        fi
        
        if [[ "${passed_check}" == "true" ]]; then
            ((passed++))
        else
            ((failed++))
            all_passed=false
        fi
        
        results+=("{\"name\":\"${name}\",\"description\":\"${description}\",\"status\":\"${status}\",\"http_code\":${http_code},\"response_time_ms\":${response_time}}")
    done

    local results_json
    results_json=$(printf '%s\n' "${results[@]}" | jq -s '.')

    local overall_status="healthy"
    if [[ "${failed}" -gt 0 ]] && [[ "${passed}" -gt 0 ]]; then
        overall_status="degraded"
    elif [[ "${failed}" -gt 0 ]] && [[ "${passed}" -eq 0 ]]; then
        overall_status="down"
    fi

    local data
    data=$(cat << EOF
{
    "target": "${target}",
    "overall_status": "${overall_status}",
    "summary": {
        "total": ${#checks[@]},
        "passed": ${passed},
        "failed": ${failed}
    },
    "checks": ${results_json}
}
EOF
)

    local next_actions
    if [[ "${all_passed}" == "true" ]]; then
        next_actions='["./deploy status"]'
    else
        next_actions='["./deploy logs", "./deploy status", "./deploy rollback"]'
    fi

    if [[ "${all_passed}" == "true" ]]; then
        json_success "test" "${data}" "${next_actions}"
    else
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "test",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "Some health checks failed", "hint": "Check logs for failed services"}],
  "next_actions": ${next_actions}
}
EOF
        return 1
    fi
}

