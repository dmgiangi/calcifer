#!/usr/bin/env bash
# =============================================================================
# Run Command - Deploy to remote server (Git-based)
# =============================================================================

CURRENT_CMD="run"

# Git repository configuration
: "${GIT_REPO_URL:=git@github.com:dmgiangi/calcifer.git}"
: "${GIT_BRANCH:=master}"

cmd_run() {
    local version_tag
    local dry_run=false
    local branch="${GIT_BRANCH}"

    version_tag=$(get_version_tag)

    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --tag)
                version_tag="$2"
                shift 2
                ;;
            --branch)
                branch="$2"
                shift 2
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
    log_progress "Target: ${DEPLOY_TARGET} (${REMOTE_HOST})"
    log_progress "Branch: ${branch}"

    # Check SSH connectivity
    if ! ssh_check; then
        json_error "Cannot connect to remote server ${REMOTE_HOST}" "Check SSH key and network connectivity"
        return 1
    fi

    local start_time
    start_time=$(date +%s)

    # Check if repo exists on remote
    local repo_exists
    repo_exists=$(ssh_exec "[ -d ${REMOTE_DEPLOY_DIR}/.git ] && echo 'yes' || echo 'no'")

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
    "target_env": "${DEPLOY_TARGET}",
    "repo_exists": ${repo_exists},
    "branch": "${branch}"
}
EOF
)
        json_success "run" "${data}" '["./deploy run --target ${DEPLOY_TARGET}"]'
        return 0
    fi

    local deploy_success=true
    local error_msg=""

    # Step 1: Clone or pull repository
    if [[ "${repo_exists}" == "no" ]]; then
        log_progress "Cloning repository to ${REMOTE_DEPLOY_DIR}..."
        if ! ssh_exec "git clone --branch ${branch} ${GIT_REPO_URL} ${REMOTE_DEPLOY_DIR}"; then
            deploy_success=false
            error_msg="Failed to clone repository"
        fi
    else
        log_progress "Pulling latest changes..."
        # Save previous version for rollback
        ssh_exec "cd ${REMOTE_DEPLOY_DIR} && echo \$(git rev-parse --short HEAD) > .deploy-version.previous" || true

        if ! ssh_exec "cd ${REMOTE_DEPLOY_DIR} && git fetch origin && git checkout ${branch} && git pull origin ${branch}"; then
            deploy_success=false
            error_msg="Failed to pull latest changes"
        fi
    fi

    # Step 2: Checkout specific version if provided
    if [[ "${deploy_success}" == "true" ]] && [[ "${version_tag}" != "$(get_git_sha)" ]]; then
        log_progress "Checking out version ${version_tag}..."
        if ! ssh_exec "cd ${REMOTE_DEPLOY_DIR} && git checkout ${version_tag}"; then
            log_progress "Version ${version_tag} not found, using HEAD"
        fi
    fi

    # Step 3: Run docker compose for the target environment
    if [[ "${deploy_success}" == "true" ]]; then
        log_progress "Starting ${DEPLOY_TARGET} services..."

        local compose_dir="${REMOTE_DEPLOY_DIR}/infrastructure/${COMPOSE_DIR}"
        local compose_cmd="cd ${compose_dir} && docker compose pull && docker compose up -d --remove-orphans"

        if ! ssh_exec "${compose_cmd}"; then
            deploy_success=false
            error_msg="Docker Compose deployment failed"
        fi
    fi

    # Save deployed version
    if [[ "${deploy_success}" == "true" ]]; then
        ssh_exec "cd ${REMOTE_DEPLOY_DIR} && echo \$(git rev-parse --short HEAD) > .deploy-version"
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    # Get actual deployed commit
    local deployed_commit
    deployed_commit=$(ssh_exec "cd ${REMOTE_DEPLOY_DIR} && git rev-parse --short HEAD 2>/dev/null" || echo "unknown")

    local data
    data=$(cat << EOF
{
    "version_tag": "${deployed_commit}",
    "previous_version": "${previous_version}",
    "target_server": "${REMOTE_HOST}",
    "target_env": "${DEPLOY_TARGET}",
    "branch": "${branch}",
    "deploy_method": "git-pull",
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
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [{"message": "${error_msg}"}],
  "next_actions": ["./deploy logs --target ${DEPLOY_TARGET}", "./deploy rollback --target ${DEPLOY_TARGET}"]
}
EOF
        return 1
    fi

    json_success "run" "${data}" "[\"./deploy test --target ${DEPLOY_TARGET}\", \"./deploy status --target ${DEPLOY_TARGET}\"]"
}

