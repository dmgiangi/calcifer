#!/usr/bin/env bash
# =============================================================================
# Build Command - Build Docker images locally
# =============================================================================

CURRENT_CMD="build"

cmd_build() {
    local components=()
    local skip_tests=false
    local version_tag
    
    version_tag=$(get_version_tag)
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --skip-tests)
                skip_tests=true
                shift
                ;;
            --tag)
                version_tag="$2"
                shift 2
                ;;
            core-server|infrastructure)
                components+=("$1")
                shift
                ;;
            *)
                shift
                ;;
        esac
    done

    # Default: build all
    if [[ ${#components[@]} -eq 0 ]]; then
        components=("core-server")
    fi

    log_progress "Building components: ${components[*]}"
    log_progress "Version tag: ${version_tag}"

    local build_results=()
    local has_errors=false
    local errors=()

    for component in "${components[@]}"; do
        log_progress "Building ${component}..."
        
        local start_time
        start_time=$(date +%s)
        local success=true
        local error_msg=""

        case "${component}" in
            core-server)
                # Build JAR first
                if [[ "${skip_tests}" == "true" ]]; then
                    if ! (cd core-server && ./mvnw package -DskipTests -q); then
                        success=false
                        error_msg="Maven build failed"
                    fi
                else
                    if ! (cd core-server && ./mvnw verify -q); then
                        success=false
                        error_msg="Maven build/tests failed"
                    fi
                fi
                
                # Build Docker image
                if [[ "${success}" == "true" ]]; then
                    local image_name="${DOCKER_REGISTRY}/${DOCKER_IMAGE_PREFIX}/core-server:${version_tag}"
                    if ! docker build -t "${image_name}" -t "${DOCKER_REGISTRY}/${DOCKER_IMAGE_PREFIX}/core-server:latest" core-server/; then
                        success=false
                        error_msg="Docker build failed"
                    fi
                fi
                ;;
                
            infrastructure)
                # Infrastructure uses pre-built images, just validate compose
                if ! docker compose -f infrastructure/docker-compose.yaml config -q; then
                    success=false
                    error_msg="Docker Compose validation failed"
                fi
                ;;
        esac

        local end_time
        end_time=$(date +%s)
        local duration=$((end_time - start_time))

        build_results+=("{\"component\":\"${component}\",\"success\":${success},\"duration_seconds\":${duration}}")
        
        if [[ "${success}" == "false" ]]; then
            has_errors=true
            errors+=("{\"component\":\"${component}\",\"message\":\"${error_msg}\"}")
        fi
    done

    # Build JSON arrays
    local results_json
    results_json=$(printf '%s\n' "${build_results[@]}" | jq -s '.')
    
    local data
    data=$(cat << EOF
{
    "version_tag": "${version_tag}",
    "components_built": ${results_json},
    "images": [
        "${DOCKER_REGISTRY}/${DOCKER_IMAGE_PREFIX}/core-server:${version_tag}"
    ]
}
EOF
)

    if [[ "${has_errors}" == "true" ]]; then
        local errors_json
        errors_json=$(printf '%s\n' "${errors[@]}" | jq -s '.')
        cat << EOF
{
  "timestamp": "$(timestamp)",
  "command": "build",
  "success": false,
  "environment": "${DEPLOY_ENV}",
  "data": ${data},
  "errors": ${errors_json},
  "next_actions": ["./deploy build --skip-tests", "./deploy logs"]
}
EOF
        return 1
    fi

    json_success "build" "${data}" '["./deploy push", "./deploy run"]'
}

