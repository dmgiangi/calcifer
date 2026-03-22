#!/usr/bin/env bash
# =============================================================================
# Common Functions for Deploy Scripts
# =============================================================================
# Provides: JSON output, SSH helpers, Docker helpers, logging
# =============================================================================

# Prevent multiple sourcing
[[ -n "${_COMMON_SH_LOADED:-}" ]] && return
_COMMON_SH_LOADED=1

# =============================================================================
# Configuration Defaults
# =============================================================================
: "${DEPLOY_ENV:=production}"
: "${DEPLOY_TARGET:=home}"  # home or cloud
: "${DOCKER_REGISTRY:=ghcr.io}"
: "${DOCKER_IMAGE_PREFIX:=calcifer}"

# Remote server configuration (per target)
if [[ "${DEPLOY_TARGET}" == "cloud" ]]; then
    : "${REMOTE_HOST:=${CLOUD_HOST:-cloud.calcifer.local}}"
    : "${REMOTE_USER:=${CLOUD_USER:-deploy}}"
    : "${REMOTE_SSH_KEY:=${CLOUD_SSH_KEY:-${HOME}/.ssh/cloud_id}}"
    : "${REMOTE_DEPLOY_DIR:=${CLOUD_DEPLOY_DIR:-/opt/calcifer}}"
    : "${COMPOSE_DIR:=cloud}"
else
    : "${REMOTE_HOST:=${HOME_HOST:-192.168.8.180}}"
    : "${REMOTE_USER:=${HOME_USER:-dmgiangi}}"
    : "${REMOTE_SSH_KEY:=${HOME_SSH_KEY:-${HOME}/.ssh/github_id}}"
    : "${REMOTE_DEPLOY_DIR:=${HOME_DEPLOY_DIR:-/opt/calcifer}}"
    : "${COMPOSE_DIR:=home}"
fi

# =============================================================================
# JSON Output Functions
# =============================================================================

# Get current timestamp in ISO8601 format
timestamp() {
    date -u +"%Y-%m-%dT%H:%M:%SZ"
}

# Output successful JSON response
# Usage: json_success "command" '{"key": "value"}'
json_success() {
    local cmd="$1"
    local data="${2:-{}}"
    local next_actions="${3:-[]}"
    
    cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "${cmd}",
  "success": true,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": ${data},
  "errors": [],
  "next_actions": ${next_actions}
}
EOF
}

# Output error JSON response
# Usage: json_error "Error message" "Hint for resolution"
json_error() {
    local message="$1"
    local hint="${2:-}"
    local cmd="${CURRENT_CMD:-unknown}"
    
    cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "${cmd}",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "server": "${REMOTE_HOST}",
  "data": {},
  "errors": [
    {
      "message": "${message}",
      "hint": "${hint}"
    }
  ],
  "next_actions": ["./deploy status", "./deploy help"]
}
EOF
}

# Output progress message (to stderr to not interfere with JSON)
log_progress() {
    echo "[$(date +%H:%M:%S)] $*" >&2
}

# =============================================================================
# SSH Helpers
# =============================================================================

# Execute command on remote server
# Usage: ssh_exec "command"
ssh_exec() {
    local cmd="$1"
    ssh -i "${REMOTE_SSH_KEY}" \
        -o StrictHostKeyChecking=accept-new \
        -o ConnectTimeout=10 \
        "${REMOTE_USER}@${REMOTE_HOST}" \
        "${cmd}"
}

# Check SSH connectivity
ssh_check() {
    ssh_exec "echo ok" &>/dev/null
}

# Copy file to remote
# Usage: scp_to "local_path" "remote_path"
scp_to() {
    local local_path="$1"
    local remote_path="$2"
    scp -i "${REMOTE_SSH_KEY}" \
        -o StrictHostKeyChecking=accept-new \
        "${local_path}" \
        "${REMOTE_USER}@${REMOTE_HOST}:${remote_path}"
}

# =============================================================================
# Docker Helpers
# =============================================================================

# Get container status as JSON array
get_container_status() {
    local format='{"name":"{{.Names}}","status":"{{.Status}}","state":"{{.State}}"}'
    docker ps -a --format "${format}" 2>/dev/null | jq -s '.'
}

# Get remote container status
get_remote_container_status() {
    ssh_exec "docker ps -a --format '{\"name\":\"{{.Names}}\",\"status\":\"{{.Status}}\",\"state\":\"{{.State}}\"}'" 2>/dev/null | jq -s '.'
}

# Check if image exists locally
image_exists() {
    local image="$1"
    docker image inspect "${image}" &>/dev/null
}

# Get image digest
get_image_digest() {
    local image="$1"
    docker image inspect "${image}" --format '{{index .RepoDigests 0}}' 2>/dev/null | cut -d@ -f2
}

# =============================================================================
# Version Helpers
# =============================================================================

# Get current git short SHA
get_git_sha() {
    git rev-parse --short HEAD 2>/dev/null || echo "unknown"
}

# Get current git branch
get_git_branch() {
    git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown"
}

# Get version tag
get_version_tag() {
    local sha
    sha=$(get_git_sha)
    echo "${sha}"
}

