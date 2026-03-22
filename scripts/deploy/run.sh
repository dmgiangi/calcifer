#!/usr/bin/env bash
# =============================================================================
# Run Command - Deploy to remote server
# =============================================================================

CURRENT_CMD="run"

cmd_run() {
    local version_tag
    local use_ansible=true
    local with_auth=false
    local dry_run=false
    
    version_tag=$(get_version_tag)
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --tag)
                version_tag="$2"
                shift 2
                ;;
            --with-auth)
                with_auth=true
                shift
                ;;
            --direct)
                use_ansible=false
                shift
                ;;
            --dry-run)
                dry_run=true
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Deploying version: ${version_tag}"
    log_progress "Target server: ${REMOTE_HOST}"

    # Check SSH connectivity
    if ! ssh_check; then
        json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH key and network connectivity"
        return 1
    fi

    local start_time
    start_time=$(date +%s)

    # Save current version for rollback
    local previous_version
    previous_version=$(ssh_exec "cat ${REMOTE_DEPLOY_DIR}/.deploy-version 2>/dev/null" || echo "none")
    
    if [[ "${dry_run}" == "true" ]]; then
        log_progress "DRY RUN - would deploy ${version_tag} (previous: ${previous_version})"
        local data
        data=$(cat << EOF
{
    "dry_run": true,
    "version_tag": "${version_tag}",
    "previous_version": "${previous_version}",
    "target_server": "${REMOTE_HOST}",
    "with_auth": ${with_auth}
}
EOF
)
        json_success "run" "${data}" '["./deploy run"]'
        return 0
    fi

    log_progress "Saving previous version for rollback: ${previous_version}"
    ssh_exec "echo '${previous_version}' > ${REMOTE_DEPLOY_DIR}/.deploy-version.previous" || true

    local deploy_success=true
    local error_msg=""

    if [[ "${use_ansible}" == "true" ]]; then
        # Use Ansible playbook
        log_progress "Running Ansible deployment..."
        
        local ansible_extra_vars="version_tag=${version_tag}"
        if [[ "${with_auth}" == "true" ]]; then
            ansible_extra_vars="${ansible_extra_vars} with_auth=true"
        fi
        
        if ! ansible-playbook \
            -i infrastructure/ansible/inventory/hosts.yml \
            infrastructure/ansible/playbooks/deploy.yml \
            -e "${ansible_extra_vars}" \
            2>&1 | tee /tmp/ansible-deploy.log; then
            deploy_success=false
            error_msg="Ansible playbook failed"
        fi
    else
        # Direct SSH deployment
        log_progress "Running direct SSH deployment..."
        
        local compose_cmd="cd ${REMOTE_DEPLOY_DIR}/infrastructure && docker compose pull && docker compose up -d"
        if [[ "${with_auth}" == "true" ]]; then
            compose_cmd="cd ${REMOTE_DEPLOY_DIR}/infrastructure && docker compose -f docker-compose.yaml -f docker-compose.auth.yaml pull && docker compose -f docker-compose.yaml -f docker-compose.auth.yaml up -d"
        fi
        
        if ! ssh_exec "${compose_cmd}"; then
            deploy_success=false
            error_msg="Docker Compose deployment failed"
        fi
    fi

    # Save deployed version
    if [[ "${deploy_success}" == "true" ]]; then
        ssh_exec "echo '${version_tag}' > ${REMOTE_DEPLOY_DIR}/.deploy-version"
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    local data
    data=$(cat << EOF
{
    "version_tag": "${version_tag}",
    "previous_version": "${previous_version}",
    "target_server": "${REMOTE_HOST}",
    "deploy_method": "$([ "${use_ansible}" == "true" ] && echo "ansible" || echo "direct")",
    "with_auth": ${with_auth},
    "duration_seconds": ${duration}
}
EOF
)

    if [[ "${deploy_success}" == "false" ]]; then
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "run",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "data": ${data},
  "errors": [{"message": "${error_msg}"}],
  "next_actions": ["./deploy logs", "./deploy rollback"]
}
EOF
        return 1
    fi

    json_success "run" "${data}" '["./deploy test", "./deploy status"]'
}

