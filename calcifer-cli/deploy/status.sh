#!/usr/bin/env bash
# =============================================================================
# Status Command - Get current state of all services
# =============================================================================

CURRENT_CMD="status"

cmd_status() {
    local check_remote=true
    local check_local=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --local)
                check_local=true
                check_remote=false
                shift
                ;;
            --all)
                check_local=true
                check_remote=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Checking deployment status..."

    # Check SSH connectivity first
    if [[ "${check_remote}" == "true" ]]; then
        if ! ssh_check; then
            json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH key and network connectivity"
            return 1
        fi
    fi

    # Collect service status
    local services="[]"
    local git_info
    local remote_version="unknown"
    
    git_info=$(cat << EOF
{
    "branch": "$(get_git_branch)",
    "commit": "$(get_git_sha)",
    "local_version": "$(get_version_tag)"
}
EOF
)

    if [[ "${check_remote}" == "true" ]]; then
        log_progress "Fetching remote container status..."
        
        # Get container status from remote
        local raw_status
        raw_status=$(ssh_exec "cd ${REMOTE_DEPLOY_DIR}/infrastructure && docker compose ps --format json 2>/dev/null" || echo "[]")
        
        # Parse and transform to our format
        services=$(echo "${raw_status}" | jq -s '
            [.[] | {
                name: .Service,
                status: .State,
                health: (if .Health == "" then "unknown" else .Health end),
                running: (.State == "running"),
                ports: .Ports
            }]
        ' 2>/dev/null || echo "[]")
        
        # Get deployed version
        remote_version=$(ssh_exec "cat ${REMOTE_DEPLOY_DIR}/.deploy-version 2>/dev/null" || echo "unknown")
    fi

    if [[ "${check_local}" == "true" ]]; then
        log_progress "Fetching local container status..."
        local local_status
        local_status=$(docker compose -f infrastructure/docker-compose.yaml ps --format json 2>/dev/null || echo "[]")
        # Merge with services if needed
    fi

    # Build health summary
    local total running healthy
    total=$(echo "${services}" | jq 'length')
    running=$(echo "${services}" | jq '[.[] | select(.running == true)] | length')
    healthy=$(echo "${services}" | jq '[.[] | select(.health == "healthy")] | length')

    # Determine overall status and next actions
    local overall_status="healthy"
    local next_actions='["./deploy test"]'
    
    if [[ "${running}" -lt "${total}" ]]; then
        overall_status="degraded"
        next_actions='["./deploy logs", "./deploy run"]'
    fi
    
    if [[ "${running}" -eq 0 ]] && [[ "${total}" -gt 0 ]]; then
        overall_status="down"
        next_actions='["./deploy run", "./deploy logs"]'
    fi
    
    if [[ "${total}" -eq 0 ]]; then
        overall_status="not_deployed"
        next_actions='["./deploy build", "./deploy run"]'
    fi

    # Output JSON
    local data
    data=$(cat << EOF
{
    "overall_status": "${overall_status}",
    "deployed_version": "${remote_version}",
    "git": ${git_info},
    "summary": {
        "total": ${total},
        "running": ${running},
        "healthy": ${healthy}
    },
    "services": ${services}
}
EOF
)

    json_success "status" "${data}" "${next_actions}"
}

