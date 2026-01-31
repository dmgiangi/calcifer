#!/usr/bin/env python3
"""
Documentation Build Script for Calcifer Project.

Reads Markdown file paths from docs.csv and converts each to PDF
using the md2pdf_remote command.

Usage:
    python utils/build_docs.py
    
Run from the project root directory.
"""

import csv
import subprocess
import sys
from pathlib import Path


def get_project_root() -> Path:
    """Get the project root directory (parent of utils/)."""
    script_dir = Path(__file__).resolve().parent
    return script_dir.parent


def read_docs_inventory(csv_path: Path) -> list[str]:
    """Read the list of Markdown files from the CSV inventory."""
    filepaths = []

    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            filepath = row.get("filepath", "").strip()
            if filepath:
                filepaths.append(filepath)

    return filepaths


def convert_to_pdf(source_path: Path, output_dir: Path) -> bool:
    """
    Convert a Markdown file to PDF using md2pdf_remote.

    Args:
        source_path: Path to the source Markdown file
        output_dir: Directory where the PDF will be saved

    Returns:
        True if conversion succeeded, False otherwise
    """
    try:
        # Use bash -i -c to load .bashrc where md2pdf_remote function is defined
        cmd = f"md2pdf_remote '{source_path}' '{output_dir}'"
        result = subprocess.run(
            ["bash", "-i", "-c", cmd],
            capture_output=True,
            text=True,
            timeout=120  # 2 minutes timeout per file
        )

        if result.returncode != 0:
            print(f"    ERROR: {result.stderr.strip() or 'Unknown error'}")
            return False

        return True

    except subprocess.TimeoutExpired:
        print("    ERROR: Conversion timed out")
        return False
    except FileNotFoundError:
        print("    ERROR: bash not found")
        return False
    except Exception as e:
        print(f"    ERROR: {e}")
        return False


def main() -> int:
    """Main entry point for the documentation build script."""
    project_root = get_project_root()
    csv_path = project_root / "utils" / "docs.csv"
    output_dir = project_root / "utils" / "docs-output"

    # Verify CSV exists
    if not csv_path.exists():
        print(f"ERROR: Inventory file not found: {csv_path}")
        return 1

    # Create output directory
    output_dir.mkdir(exist_ok=True)
    print(f"Output directory: {output_dir}")
    print()

    # Read inventory
    filepaths = read_docs_inventory(csv_path)

    if not filepaths:
        print("WARNING: No files found in inventory")
        return 0

    print(f"Found {len(filepaths)} documentation files to process")
    print("=" * 50)

    # Process each file
    success_count = 0
    failed_files = []

    for i, filepath in enumerate(filepaths, start=1):
        source_path = project_root / filepath

        print(f"Processing {i}/{len(filepaths)}: {filepath}")

        if not source_path.exists():
            print(f"    WARNING: File not found, skipping")
            failed_files.append(filepath)
            continue

        if convert_to_pdf(source_path, output_dir):
            print("    OK")
            success_count += 1
        else:
            failed_files.append(filepath)

    # Summary
    print()
    print("=" * 50)
    print(f"Build complete: {success_count}/{len(filepaths)} files converted")

    if failed_files:
        print()
        print("Failed files:")
        for f in failed_files:
            print(f"  - {f}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
