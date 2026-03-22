#!/usr/bin/env bash
# =============================================================================
# Rollback Command - Rollback to previous version
# =============================================================================

CURRENT_CMD="rollback"

cmd_rollback() {
    local confirm=false
    local dry_run=false
    local target_version=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --confirm|-y)
                confirm=true
                shift
                ;;
            --dry-run)
                dry_run=true
                shift
                ;;
            --to)
                target_version="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Preparing rollback..."

    # Check SSH connectivity
    if ! ssh_check; then
        json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH connectivity"
        return 1
    fi

    # Get current and previous versions
    local current_version
    local previous_version
    
    current_version=$(ssh_exec "cat ${REMOTE_DEPLOY_DIR}/.deploy-version 2>/dev/null" || echo "unknown")
    previous_version=$(ssh_exec "cat ${REMOTE_DEPLOY_DIR}/.deploy-version.previous 2>/dev/null" || echo "none")
    
    # Determine target version
    if [[ -z "${target_version}" ]]; then
        target_version="${previous_version}"
    fi

    if [[ "${target_version}" == "none" ]] || [[ "${target_version}" == "unknown" ]]; then
        json_error "No previous version available for rollback" "Deploy a version first, then try again"
        return 1
    fi

    local data
    data=$(cat << EOF
{
    "current_version": "${current_version}",
    "target_version": "${target_version}",
    "dry_run": ${dry_run}
}
EOF
)

    if [[ "${dry_run}" == "true" ]]; then
        log_progress "DRY RUN - would rollback from ${current_version} to ${target_version}"
        json_success "rollback" "${data}" '["./deploy rollback --confirm"]'
        return 0
    fi

    if [[ "${confirm}" != "true" ]]; then
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "rollback",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "Rollback requires confirmation", "hint": "Use --confirm or -y flag"}],
  "next_actions": ["./deploy rollback --confirm", "./deploy rollback --dry-run"]
}
EOF
        return 1
    fi

    log_progress "Rolling back from ${current_version} to ${target_version}..."

    local start_time
    start_time=$(date +%s)
    local rollback_success=true
    local error_msg=""

    # Execute rollback
    # Save current as the new "previous" for potential re-rollback
    ssh_exec "echo '${current_version}' > ${REMOTE_DEPLOY_DIR}/.deploy-version.previous" || true

    # Pull and restart with previous version
    local rollback_cmd="cd ${REMOTE_DEPLOY_DIR}/infrastructure && IMAGE_TAG=${target_version} docker compose pull && IMAGE_TAG=${target_version} docker compose up -d"
    
    if ! ssh_exec "${rollback_cmd}"; then
        rollback_success=false
        error_msg="Failed to restart services with version ${target_version}"
    fi

    # Update current version
    if [[ "${rollback_success}" == "true" ]]; then
        ssh_exec "echo '${target_version}' > ${REMOTE_DEPLOY_DIR}/.deploy-version"
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    data=$(cat << EOF
{
    "previous_version": "${current_version}",
    "rolled_back_to": "${target_version}",
    "duration_seconds": ${duration}
}
EOF
)

    if [[ "${rollback_success}" == "true" ]]; then
        json_success "rollback" "${data}" '["./deploy test", "./deploy status"]'
    else
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "rollback",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "${error_msg}"}],
  "next_actions": ["./deploy logs", "./deploy status"]
}
EOF
        return 1
    fi
}

