#!/usr/bin/env python3
"""Provision Cloud OTLP credentials without emitting or staging plaintext."""

from __future__ import annotations

import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "clusters/calcifer-cloud/apps/observability/public-otlp-auth.sops.yaml"
BASHRC = Path("/home/dmgiangi/.bashrc")
BACKUP = Path("/home/dmgiangi/.bashrc.before-calcifer-otlp")
REPOSITORY = "dmgiangi/peer-reviewer"
KEY = "CALCIFER_OTLP_API_KEY"
ENTRY = re.compile(r"^\s*(?:export\s+)?CALCIFER_OTLP_API_KEY=", re.MULTILINE)
ALIASES = ("peer-reviewer-local", "peer-reviewer-github")


def run(arguments: list[str], payload: bytes | None = None) -> bytes:
    result = subprocess.run(arguments, input=payload, capture_output=True, check=False)
    if result.returncode:
        # Tool errors can contain credentials. Never include captured output.
        raise RuntimeError(f"{arguments[0]} failed (exit {result.returncode}); output suppressed")
    return result.stdout


def auth_config(tokens: tuple[str, str]) -> dict:
    return {"users": [
        {"name": alias, "bearer_token": token, "dump_request_on_errors": False,
         "headers": ["Authorization:"], "url_map": [{
             "src_paths": ["^/v1/traces$", "^/v1/logs$", "^/v1/metrics$"],
             "url_prefix": f"http://alloy-otlp-gateway.monitoring.svc.cluster.local:{port}",
         }]}
        for alias, token, port in zip(ALIASES, tokens, (4318, 4319))
    ]}


def write_exclusive(path: Path, content: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with open(fd, "wb", closefd=True) as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def main() -> None:
    os.umask(0o077)
    if DESTINATION.exists() or DESTINATION.is_symlink() or BACKUP.exists() or BACKUP.is_symlink():
        raise RuntimeError("destination or backup already exists; refusing to rotate/overwrite")
    fd = os.open(BASHRC, os.O_RDWR | os.O_NOFOLLOW)
    with os.fdopen(fd, "r+", encoding="utf-8") as startup:
        fcntl.flock(startup, fcntl.LOCK_EX)
        original = startup.read()
        if ENTRY.search(original):
            raise RuntimeError("local export already exists; approval required to replace it")
        names = json.loads(run(["gh", "secret", "list", "--repo", REPOSITORY, "--json", "name"]))
        if any(item["name"] == KEY for item in names):
            raise RuntimeError("GitHub secret already exists; approval required to replace it")
        tokens = (secrets.token_urlsafe(32), secrets.token_urlsafe(32))
        if secrets.compare_digest(*tokens):
            raise RuntimeError("credential independence check failed")
        document = {"apiVersion": "v1", "kind": "Secret", "type": "Opaque",
                    "metadata": {"name": "public-otlp-auth", "namespace": "monitoring"},
                    "stringData": {"auth.yaml": json.dumps(auth_config(tokens))}}
        encrypted = run(["sops", "--encrypt", "--input-type", "json", "--output-type", "yaml",
                         "--filename-override", str(DESTINATION), "/dev/stdin"],
                        json.dumps(document).encode())
        if any(token.encode() in encrypted for token in tokens):
            raise RuntimeError("encryption check failed")
        write_exclusive(DESTINATION, encrypted)
        if json.loads(run(["sops", "filestatus", str(DESTINATION)])).get("encrypted") is not True:
            raise RuntimeError("SOPS status check failed")
        restored = json.loads(run(["sops", "--decrypt", "--output-type", "json", str(DESTINATION)]))
        if json.loads(restored["stringData"]["auth.yaml"]) != auth_config(tokens):
            raise RuntimeError("encrypted credential round-trip failed")
        print("PASS: independent 256-bit keys encrypted and round-trip verified", flush=True)
        write_exclusive(BACKUP, original.encode())
        run(["gh", "secret", "set", KEY, "--repo", REPOSITORY], tokens[1].encode())
        print("PASS: GitHub secret submitted via stdin", flush=True)
        os.fchmod(startup.fileno(), 0o600)
        startup.seek(0, os.SEEK_END)
        startup.write(("" if original.endswith("\n") else "\n") +
                      "\n# calcifer-cloud OTLP: peer-reviewer-local\n" +
                      f"export {KEY}={tokens[0]}\n")
        startup.flush()
        os.fsync(startup.fileno())
        startup.seek(0)
        updated = startup.read()
        match = re.search(r"^export CALCIFER_OTLP_API_KEY=([^\n]+)$", updated, re.MULTILINE)
        if not updated.startswith(original) or not match or not secrets.compare_digest(match[1], tokens[0]):
            raise RuntimeError("local credential verification failed")
        names = json.loads(run(["gh", "secret", "list", "--repo", REPOSITORY, "--json", "name"]))
        if not any(item["name"] == KEY for item in names):
            raise RuntimeError("GitHub secret metadata verification failed")
        print("PASS: local export/permissions and GitHub metadata verified; no temporary plaintext")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, ValueError, KeyError):
        raise SystemExit("Provisioning stopped. Inspect destination metadata before retrying; sensitive details suppressed.") from None