#!/usr/bin/env bash
# =============================================================================
# Sync Env Command - Copy .env files to remote server
# =============================================================================

CURRENT_CMD="sync-env"

cmd_sync_env() {
    local dry_run=false
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --dry-run)
                dry_run=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Syncing .env files to ${DEPLOY_TARGET} (${REMOTE_HOST})..."

    # Check SSH connectivity
    if ! ssh_check; then
        json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH connectivity"
        return 1
    fi

    # Define env files based on target
    local env_files=()
    local base_dir="${SCRIPT_DIR}/infrastructure"
    
    if [[ "${DEPLOY_TARGET}" == "cloud" ]]; then
        env_files=(
            "${base_dir}/cloud/.env"
        )
    else
        env_files=(
            "${base_dir}/home/.env"
        )
    fi

    # Check if local env files exist
    local missing_files=()
    for env_file in "${env_files[@]}"; do
        if [[ ! -f "${env_file}" ]]; then
            missing_files+=("${env_file}")
        fi
    done

    if [[ ${#missing_files[@]} -gt 0 ]]; then
        local missing_json
        missing_json=$(printf '"%s",' "${missing_files[@]}" | sed 's/,$//')
        json_error "Missing .env files: [${missing_json}]" "Create the .env files locally first"
        return 1
    fi

    if [[ "${dry_run}" == "true" ]]; then
        local files_json
        files_json=$(printf '"%s",' "${env_files[@]}" | sed 's/,$//')
        local data
        data=$(cat << EOF
{
    "dry_run": true,
    "target": "${DEPLOY_TARGET}",
    "files_to_sync": [${files_json}]
}
EOF
)
        json_success "sync-env" "${data}" "[\"./deploy sync-env --target ${DEPLOY_TARGET}\"]"
        return 0
    fi

    local start_time
    start_time=$(date +%s)
    local sync_success=true
    local synced_files=()
    local error_msg=""

    # Ensure remote directories exist
    log_progress "Creating remote directories..."
    ssh_exec "mkdir -p ${REMOTE_DEPLOY_DIR}/infrastructure/${COMPOSE_DIR}" || true

    # Copy each env file
    for env_file in "${env_files[@]}"; do
        local relative_path="${env_file#${base_dir}/}"
        local remote_path="${REMOTE_DEPLOY_DIR}/infrastructure/${relative_path}"
        
        log_progress "Copying ${relative_path}..."
        
        if scp -i "${REMOTE_SSH_KEY}" \
            -o StrictHostKeyChecking=accept-new \
            "${env_file}" \
            "${REMOTE_USER}@${REMOTE_HOST}:${remote_path}"; then
            synced_files+=("${relative_path}")
        else
            sync_success=false
            error_msg="Failed to copy ${relative_path}"
            break
        fi
    done

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    local synced_json
    synced_json=$(printf '"%s",' "${synced_files[@]}" | sed 's/,$//')

    local data
    data=$(cat << EOF
{
    "target": "${DEPLOY_TARGET}",
    "server": "${REMOTE_HOST}",
    "synced_files": [${synced_json}],
    "duration_seconds": ${duration}
}
EOF
)

    if [[ "${sync_success}" == "true" ]]; then
        json_success "sync-env" "${data}" "[\"./deploy run --target ${DEPLOY_TARGET}\"]"
    else
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "sync-env",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "${error_msg}"}],
  "next_actions": ["./deploy sync-env --target ${DEPLOY_TARGET}"]
}
EOF
        return 1
    fi
}

