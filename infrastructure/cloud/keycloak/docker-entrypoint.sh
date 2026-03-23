#!/bin/bash
# Keycloak entrypoint: substitutes env vars in realm templates and starts Keycloak
# Uses --import-realm for reproducible configuration
# If realm already exists, import is safely skipped by Keycloak

set -e

IMPORT_DIR="/opt/keycloak/data/import"
TEMPLATE_DIR="/opt/keycloak/data/import-templates"

mkdir -p "${IMPORT_DIR}"

# Only substitute our known env vars (protect Keycloak expressions like ${CLAIM.email})
ENVSUBST_VARS='${GOOGLE_CLIENT_ID} ${GOOGLE_CLIENT_SECRET} ${KEYCLOAK_CLIENT_ID} ${KEYCLOAK_CLIENT_SECRET} ${API_CLIENT_ID} ${API_CLIENT_SECRET}'

# Process all JSON templates
if [ -d "${TEMPLATE_DIR}" ]; then
    for template in "${TEMPLATE_DIR}"/*.json; do
        [ -f "$template" ] || continue
        filename=$(basename "$template")
        echo "[ENTRYPOINT] Processing template: ${filename}"
        envsubst "${ENVSUBST_VARS}" < "$template" > "${IMPORT_DIR}/${filename}"
    done
    echo "[ENTRYPOINT] Templates processed. Starting Keycloak with --import-realm"
else
    echo "[ENTRYPOINT] No templates found in ${TEMPLATE_DIR}"
fi

# Start Keycloak with import-realm flag
exec /opt/keycloak/bin/kc.sh start --import-realm "$@"

