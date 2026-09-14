#!/usr/bin/env python3
"""Set the local authorization password in both encrypted SOPS Secrets.

The password is accepted only through stdin/a hidden TTY prompt. It is passed
to htpasswd through stdin and the generated bcrypt hash is passed directly to
SOPS. Neither the password nor the hash is printed.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CLOUD = ROOT / "clusters/apps/authorization-server/overlays/cloud/authorization-server-secrets.sops.yaml"
DEFAULT_HOME = ROOT / "clusters/apps/authorization-server/overlays/home/authorization-server-secrets.sops.yaml"
PASSWORD_PATH = '["data"]["AUTH_LOCAL_LOGIN_PASSWORD_HASH"]'


def run_checked(arguments: list[str], *, input_data: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    result = subprocess.run(
        arguments,
        input=input_data,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        # Do not include command arguments: the SOPS --set argument contains the hash.
        raise RuntimeError(f"command failed with exit code {result.returncode}: {arguments[0]}")
    return result


def assert_encrypted(path: Path) -> None:
    result = run_checked(["sops", "filestatus", str(path)])
    try:
        status = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"invalid SOPS status for {path}") from exc
    if status.get("encrypted") is not True:
        raise RuntimeError(f"refusing to edit unencrypted file: {path}")


def read_password() -> bytes:
    if sys.stdin.isatty():
        value = getpass.getpass("Password: ")
        if not value:
            raise RuntimeError("password must not be empty")
        return value.encode("utf-8")

    value = sys.stdin.buffer.readline()
    if value.endswith(b"\r\n"):
        value = value[:-2]
    elif value.endswith(b"\n"):
        value = value[:-1]
    if not value:
        raise RuntimeError("password must be one non-empty line on stdin")
    if sys.stdin.buffer.read(1):
        raise RuntimeError("stdin must contain exactly one password line")
    return value


def bcrypt_hash(password: bytes, username: str, cost: int) -> str:
    if shutil.which("htpasswd") is None:
        raise RuntimeError("htpasswd is required to generate the bcrypt hash")
    result = run_checked(
        ["htpasswd", "-nBiC", str(cost), username],
        input_data=password + b"\n",
    )
    lines = [line for line in result.stdout.splitlines() if line]
    if len(lines) != 1 or b":" not in lines[0]:
        raise RuntimeError("htpasswd did not return one bcrypt credential")
    generated_username, generated_hash = lines[0].split(b":", 1)
    if generated_username.decode("utf-8") != username or not generated_hash.startswith((b"$2a$", b"$2b$", b"$2y$")):
        raise RuntimeError("htpasswd did not return a bcrypt hash")
    return "{bcrypt}" + generated_hash.decode("ascii")


def set_hash(path: Path, hash_value: str) -> None:
    # Secret.data stores base64-encoded bytes; SOPS encrypts the value but does
    # not perform the Kubernetes data encoding for us.
    encoded_hash = base64.b64encode(hash_value.encode("ascii")).decode("ascii")
    expression = f"{PASSWORD_PATH} {json.dumps(encoded_hash)}"
    run_checked(["sops", "--set", expression, "--in-place", str(path)])
    assert_encrypted(path)
    extracted = run_checked(["sops", "--decrypt", "--extract", PASSWORD_PATH, str(path)]).stdout.strip()
    try:
        decoded = base64.b64decode(extracted, validate=True)
    except ValueError as exc:
        raise RuntimeError(f"SOPS hash verification failed for {path}") from exc
    if not decoded.startswith((b"{bcrypt}$2a$", b"{bcrypt}$2b$", b"{bcrypt}$2y$")):
        raise RuntimeError(f"SOPS hash verification failed for {path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--username", default="dem.gianluigi@gmail.com")
    parser.add_argument("--cost", type=int, default=12, choices=range(4, 18), metavar="4..17")
    parser.add_argument("--cloud-secret", type=Path, default=DEFAULT_CLOUD)
    parser.add_argument("--home-secret", type=Path, default=DEFAULT_HOME)
    args = parser.parse_args()

    paths = [args.cloud_secret.resolve(), args.home_secret.resolve()]
    if len(set(paths)) != 2:
        raise RuntimeError("Cloud and Home Secret paths must be different")
    for path in paths:
        if not path.is_file():
            raise RuntimeError(f"Secret file does not exist: {path}")
        assert_encrypted(path)

    password = read_password()
    try:
        hash_value = bcrypt_hash(password, args.username, args.cost)
    finally:
        del password

    parent = paths[0].parent
    with tempfile.TemporaryDirectory(prefix=".authorization-server-secrets-", dir=parent) as temporary:
        temporary_dir = Path(temporary)
        staged: list[Path] = []
        backups: list[Path] = []
        for index, path in enumerate(paths):
            staged_path = temporary_dir / f"secret-{index}.sops.yaml"
            backup_path = temporary_dir / f"backup-{index}.sops.yaml"
            shutil.copy2(path, staged_path)
            shutil.copy2(path, backup_path)
            set_hash(staged_path, hash_value)
            staged.append(staged_path)
            backups.append(backup_path)

        replaced: list[Path] = []
        try:
            for path, staged_path in zip(paths, staged):
                os.replace(staged_path, path)
                replaced.append(path)
        except Exception:
            for path, backup_path in zip(replaced, backups):
                os.replace(backup_path, path)
            raise

    del hash_value
    print("Updated both encrypted authorization-server SOPS Secrets.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
