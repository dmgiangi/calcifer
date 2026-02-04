#!/bin/bash
# Build documentation PDFs using Docker with Quarto
#
# Usage:
#   ./utils/build_docs_docker.sh
#
# Run from the project root directory.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
IMAGE_NAME="calcifer-docs-builder"
DOCKERFILE_PATH="$SCRIPT_DIR/Dockerfile"

echo "=== Calcifer Documentation Builder (Docker) ==="
echo "Project root: $PROJECT_ROOT"
echo ""

# Build Docker image if needed
echo "Building Docker image..."
docker build -t "$IMAGE_NAME" -f "$DOCKERFILE_PATH" "$SCRIPT_DIR" --quiet

# Create output directory
mkdir -p "$PROJECT_ROOT/utils/docs-output"

# Read files from docs.csv and process each one
echo ""
echo "Processing documentation files..."
echo "=================================================="

# Skip header line and process each file (CSV format: filepath,output_filename)
tail -n +2 "$PROJECT_ROOT/utils/docs.csv" | while IFS=',' read -r filepath output_filename || [[ -n "$filepath" ]]; do
    # Skip empty lines
    [[ -z "$filepath" ]] && continue

    # Determine the working directory and filename
    source_dir=$(dirname "$filepath")
    source_file=$(basename "$filepath")

    # Use output_filename from CSV, or default to source filename with .pdf extension
    if [[ -z "$output_filename" ]]; then
        output_file="${source_file%.md}.pdf"
    else
        output_file="$output_filename"
    fi

    echo "Processing: $filepath -> $output_file"

    # Handle root-level files vs subdirectory files
    if [[ "$source_dir" == "." ]]; then
        workdir="/data"
    else
        workdir="/data/$source_dir"
    fi

    # Run Quarto in Docker
    # For files with Mermaid diagrams, we need to use .qmd extension
    # Create a temporary .qmd copy, render, then clean up
    # Also copy before-body.tex to a temp directory for include-before-body
    qmd_file="${source_file%.md}.qmd"

    if docker run --rm \
        -v "$PROJECT_ROOT:/data" \
        -w "$workdir" \
        "$IMAGE_NAME" \
        sh -c "
            # Copy before-body.tex and resize-images.lua to _quarto_temp/utils/ relative to working directory
            mkdir -p _quarto_temp/utils 2>/dev/null || true
            cp /data/utils/before-body.tex _quarto_temp/utils/before-body.tex 2>/dev/null || true
            cp /data/utils/resize-images.lua _quarto_temp/utils/resize-images.lua 2>/dev/null || true

            # Copy source to .qmd for Mermaid support
            cp '$source_file' '$qmd_file'

            # Render PDF
            quarto render '$qmd_file' --to pdf

            # Move output and clean up
            mv '${qmd_file%.qmd}.pdf' '/data/utils/docs-output/$output_file'
            rm -f '$qmd_file'
            rm -rf _quarto_temp 2>/dev/null || true
        " 2>&1 | tail -10; then
        echo "    OK: $output_file"
    else
        echo "    FAILED: $filepath"
    fi
    echo ""
done

echo "=================================================="
echo "Build complete. PDFs are in: $PROJECT_ROOT/utils/docs-output/"
ls -la "$PROJECT_ROOT/utils/docs-output/"

