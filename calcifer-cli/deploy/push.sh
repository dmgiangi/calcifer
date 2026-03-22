#!/usr/bin/env bash
# =============================================================================
# Push Command - Push Docker images to registry
# =============================================================================

CURRENT_CMD="push"

cmd_push() {
    local version_tag
    version_tag=$(get_version_tag)
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --tag)
                version_tag="$2"
                shift 2
                ;;
            *)
                shift
                ;;
        esac
    done

    log_progress "Pushing images with tag: ${version_tag}"

    # Check if images exist locally
    local image_name="${DOCKER_REGISTRY}/${DOCKER_IMAGE_PREFIX}/core-server:${version_tag}"
    
    if ! image_exists "${image_name}"; then
        json_error "Image not found: ${image_name}" "Run './deploy build' first"
        return 1
    fi

    # Check registry login
    log_progress "Checking registry authentication..."
    if ! docker login "${DOCKER_REGISTRY}" --get-login &>/dev/null; then
        json_error "Not logged in to registry ${DOCKER_REGISTRY}" "Run 'docker login ${DOCKER_REGISTRY}' first"
        return 1
    fi

    local push_results=()
    local has_errors=false

    # Push versioned tag
    log_progress "Pushing ${image_name}..."
    local start_time
    start_time=$(date +%s)
    
    if docker push "${image_name}" &>/dev/null; then
        local digest
        digest=$(get_image_digest "${image_name}")
        push_results+=("{\"image\":\"${image_name}\",\"success\":true,\"digest\":\"${digest}\"}")
    else
        push_results+=("{\"image\":\"${image_name}\",\"success\":false}")
        has_errors=true
    fi

    # Push latest tag
    local latest_image="${DOCKER_REGISTRY}/${DOCKER_IMAGE_PREFIX}/core-server:latest"
    log_progress "Pushing ${latest_image}..."
    
    if docker push "${latest_image}" &>/dev/null; then
        push_results+=("{\"image\":\"${latest_image}\",\"success\":true}")
    else
        push_results+=("{\"image\":\"${latest_image}\",\"success\":false}")
        has_errors=true
    fi

    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    local results_json
    results_json=$(printf '%s\n' "${push_results[@]}" | jq -s '.')

    local data
    data=$(cat << EOF
{
    "version_tag": "${version_tag}",
    "registry": "${DOCKER_REGISTRY}",
    "duration_seconds": ${duration},
    "pushed_images": ${results_json}
}
EOF
)

    if [[ "${has_errors}" == "true" ]]; then
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "push",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "data": ${data},
  "errors": [{"message": "Some images failed to push"}],
  "next_actions": ["./deploy push"]
}
EOF
        return 1
    fi

    json_success "push" "${data}" '["./deploy run"]'
}

