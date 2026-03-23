#!/bin/bash
# Keycloak entrypoint: substitutes env vars in realm templates and starts Keycloak
# Uses --import-realm for reproducible configuration
# If realm already exists, import is safely skipped by Keycloak
#
# Note: envsubst is not available in the Keycloak UBI image, so we use sed.
# Only our known env vars are substituted (Keycloak expressions like ${CLAIM.email} are preserved).

set -e

IMPORT_DIR="/opt/keycloak/data/import"
TEMPLATE_DIR="/opt/keycloak/data/import-templates"

mkdir -p "${IMPORT_DIR}"

# Process all JSON templates using sed (envsubst not available in Keycloak image)
if [ -d "${TEMPLATE_DIR}" ]; then
    for template in "${TEMPLATE_DIR}"/*.json; do
        [ -f "$template" ] || continue
        filename=$(basename "$template")
        echo "[ENTRYPOINT] Processing template: ${filename}"
        sed \
            -e "s|\${GOOGLE_CLIENT_ID}|${GOOGLE_CLIENT_ID}|g" \
            -e "s|\${GOOGLE_CLIENT_SECRET}|${GOOGLE_CLIENT_SECRET}|g" \
            -e "s|\${KEYCLOAK_CLIENT_ID}|${KEYCLOAK_CLIENT_ID}|g" \
            -e "s|\${KEYCLOAK_CLIENT_SECRET}|${KEYCLOAK_CLIENT_SECRET}|g" \
            -e "s|\${API_CLIENT_ID}|${API_CLIENT_ID}|g" \
            -e "s|\${API_CLIENT_SECRET}|${API_CLIENT_SECRET}|g" \
            "$template" > "${IMPORT_DIR}/${filename}"
    done
    echo "[ENTRYPOINT] Templates processed. Starting Keycloak with --import-realm"
else
    echo "[ENTRYPOINT] No templates found in ${TEMPLATE_DIR}"
fi

# Start Keycloak with import-realm flag
exec /opt/keycloak/bin/kc.sh start --import-realm "$@"

