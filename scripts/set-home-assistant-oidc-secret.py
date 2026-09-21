#!/usr/bin/env python3
"""Generate and synchronize the Home Assistant OIDC client secret."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
AUTH_SECRETS = (
    ROOT / "clusters/apps/authorization-server/overlays/cloud/authorization-server-secrets.sops.yaml",
    ROOT / "clusters/apps/authorization-server/overlays/home/authorization-server-secrets.sops.yaml",
)
HA_SECRET = ROOT / "clusters/calcifer-home/apps/home-automation/home-assistant/home-assistant-secrets.sops.yaml"
KEY = "HOME_ASSISTANT_OIDC_CLIENT_SECRET"


def run(arguments: list[str], input_data: bytes | None = None) -> bytes:
    result = subprocess.run(arguments, input=input_data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError(f"{arguments[0]} failed with exit code {result.returncode}")
    return result.stdout


def decrypt(path: Path) -> dict:
    status = json.loads(run(["sops", "filestatus", str(path)]))
    if status.get("encrypted") is not True:
        raise RuntimeError(f"refusing to read unencrypted Secret: {path}")
    return json.loads(run(["sops", "--decrypt", "--output-type", "json", str(path)]))


def encrypt(data: dict, destination: Path) -> None:
    plaintext = json.dumps(data, separators=(",", ":")).encode()
    encrypted = run(
        [
            "sops",
            "--encrypt",
            "--input-type",
            "json",
            "--output-type",
            "yaml",
            "--filename-override",
            str(destination),
            "/dev/stdin",
        ],
        plaintext,
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as temporary:
            temporary.write(encrypted)
        os.replace(temporary_name, destination)
    except Exception:
        os.unlink(temporary_name)
        raise
    status = json.loads(run(["sops", "filestatus", str(destination)]))
    if status.get("encrypted") is not True:
        raise RuntimeError(f"generated Secret is not encrypted: {destination}")


def main() -> None:
    value = secrets.token_urlsafe(48)
    encoded = base64.b64encode(value.encode()).decode()

    for path in AUTH_SECRETS:
        document = decrypt(path)
        document.setdefault("data", {})[KEY] = encoded
        encrypt(document, path)

    home_assistant = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": "home-assistant-secrets", "namespace": "home-automation"},
        "type": "Opaque",
        "stringData": {"secrets.yaml": f"oidc_client_secret: {json.dumps(value)}\n"},
    }
    encrypt(home_assistant, HA_SECRET)
    print("Updated encrypted Home Assistant OIDC Secrets.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, json.JSONDecodeError) as error:
        raise SystemExit(f"error: {error}") from None
