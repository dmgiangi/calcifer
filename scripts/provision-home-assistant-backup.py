#!/usr/bin/env python3
"""Provision private Azure backup storage and its SOPS Secret."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SECRET = ROOT / "clusters/calcifer-home/apps/home-assistant/backup-secrets.sops.yaml"
ACCOUNT = "calciferobs"
RESOURCE_GROUP = "rg-calcifer-westeurope-001"
CONTAINER = "home-assistant-backups"


def run(arguments: list[str], input_data: bytes | None = None) -> bytes:
    result = subprocess.run(
        arguments,
        input=input_data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed with exit code {result.returncode}")
    return result.stdout


def existing_password() -> str:
    if not SECRET.exists():
        return secrets.token_urlsafe(48)
    status = json.loads(run(["sops", "filestatus", str(SECRET)]))
    if status.get("encrypted") is not True:
        raise RuntimeError(f"refusing to read unencrypted Secret: {SECRET}")
    document = json.loads(
        run(["sops", "--decrypt", "--output-type", "json", str(SECRET)])
    )
    return document["stringData"]["RESTIC_PASSWORD"]


def encrypt(document: dict) -> None:
    encrypted = run(
        [
            "sops",
            "--encrypt",
            "--input-type",
            "json",
            "--output-type",
            "yaml",
            "--filename-override",
            str(SECRET),
            "/dev/stdin",
        ],
        json.dumps(document, separators=(",", ":")).encode(),
    )
    SECRET.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{SECRET.name}.", dir=SECRET.parent
    )
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(encrypted)
        os.replace(temporary_name, SECRET)
    except Exception:
        os.unlink(temporary_name)
        raise


def main() -> None:
    run(
        [
            "az",
            "storage",
            "container-rm",
            "create",
            "--storage-account",
            ACCOUNT,
            "--resource-group",
            RESOURCE_GROUP,
            "--name",
            CONTAINER,
            "--public-access",
            "off",
            "--output",
            "none",
        ]
    )
    expiry = (datetime.now(UTC) + timedelta(days=365)).strftime("%Y-%m-%dT%H:%MZ")
    sas = run(
        [
            "az",
            "storage",
            "container",
            "generate-sas",
            "--account-name",
            ACCOUNT,
            "--name",
            CONTAINER,
            "--permissions",
            "racwdl",
            "--expiry",
            expiry,
            "--https-only",
            "--auth-mode",
            "key",
            "--output",
            "tsv",
        ]
    ).decode().strip()
    if not sas:
        raise RuntimeError("Azure CLI returned an empty SAS token")

    encrypt(
        {
            "apiVersion": "v1",
            "kind": "Secret",
            "metadata": {
                "name": "home-assistant-backup",
                "namespace": "home-assistant",
            },
            "type": "Opaque",
            "stringData": {
                "AZURE_ACCOUNT_NAME": ACCOUNT,
                "AZURE_ACCOUNT_SAS": sas,
                "RESTIC_PASSWORD": existing_password(),
            },
        }
    )
    print(f"Provisioned private backup container and encrypted Secret; SAS expires {expiry}.")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, RuntimeError, json.JSONDecodeError) as error:
        raise SystemExit(f"error: {error}") from None
