#!/usr/bin/env bash
# =============================================================================
# Rollback Command - Rollback to previous version (Git-based)
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

    log_progress "Preparing rollback for ${DEPLOY_TARGET}..."

    # Check SSH connectivity
    if ! ssh_check; then
        json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH connectivity"
        return 1
    fi

    # Get current and previous versions (git commits)
    local current_version
    local previous_version

    current_version=$(ssh_exec "cd ${REMOTE_DEPLOY_DIR} && git rev-parse --short HEAD 2>/dev/null" || echo "unknown")
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
    "target_env": "${DEPLOY_TARGET}",
    "dry_run": ${dry_run}
}
EOF
)

    if [[ "${dry_run}" == "true" ]]; then
        log_progress "DRY RUN - would rollback from ${current_version} to ${target_version}"
        json_success "rollback" "${data}" "[\"./deploy rollback --target ${DEPLOY_TARGET} --confirm\"]"
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
  "next_actions": ["./deploy rollback --target ${DEPLOY_TARGET} --confirm", "./deploy rollback --target ${DEPLOY_TARGET} --dry-run"]
}
EOF
        return 1
    fi

    log_progress "Rolling back from ${current_version} to ${target_version}..."

    local start_time
    start_time=$(date +%s)
    local rollback_success=true
    local error_msg=""

    # Save current as the new "previous" for potential re-rollback
    ssh_exec "echo '${current_version}' > ${REMOTE_DEPLOY_DIR}/.deploy-version.previous" || true

    # Git checkout to target version
    log_progress "Checking out ${target_version}..."
    if ! ssh_exec "cd ${REMOTE_DEPLOY_DIR} && git checkout ${target_version}"; then
        rollback_success=false
        error_msg="Failed to checkout version ${target_version}"
    fi

    # Restart services
    if [[ "${rollback_success}" == "true" ]]; then
        log_progress "Restarting ${DEPLOY_TARGET} services..."
        local compose_dir="${REMOTE_DEPLOY_DIR}/infrastructure/${COMPOSE_DIR}"

        if ! ssh_exec "cd ${compose_dir} && docker compose up -d --remove-orphans"; then
            rollback_success=false
            error_msg="Failed to restart services"
        fi
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
    "target_env": "${DEPLOY_TARGET}",
    "duration_seconds": ${duration}
}
EOF
)

    if [[ "${rollback_success}" == "true" ]]; then
        json_success "rollback" "${data}" "[\"./deploy test --target ${DEPLOY_TARGET}\", \"./deploy status --target ${DEPLOY_TARGET}\"]"
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
  "next_actions": ["./deploy logs --target ${DEPLOY_TARGET}", "./deploy status --target ${DEPLOY_TARGET}"]
}
EOF
        return 1
    fi
}

