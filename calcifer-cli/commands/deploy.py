"""
Deploy command - push changes and restart services without full clean+bootstrap.
"""

import argparse
import subprocess
from typing import List
from pathlib import Path

from utils.config import Config


def print_header(text: str) -> None:
    print(f"\n{'─' * 60}")
    print(f"  {text}")
    print(f"{'─' * 60}")


def print_step(icon: str, text: str) -> None:
    print(f"  {icon}  {text}")


def ssh_run(config, command: str, check: bool = True) -> subprocess.CompletedProcess:
    target = config.current_target
    ssh_cmd = [
        "ssh", "-i", target.ssh_key,
        "-o", "StrictHostKeyChecking=no",
        f"{target.user}@{target.host}",
        command,
    ]
    return subprocess.run(ssh_cmd, capture_output=True, text=True, check=check)


def cmd_deploy(args: List[str]) -> int:
    """Deploy changes to remote server without full clean+bootstrap."""
    parser = argparse.ArgumentParser(prog="calcifer-cli.py deploy")
    parser.add_argument("--target", choices=["home", "cloud"], default="cloud")
    parser.add_argument("--sync-env", action="store_true",
                        help="Also sync .env file to server")
    parser.add_argument("--restart", nargs="*", default=None,
                        help="Restart specific services (default: all changed)")
    parser.add_argument("--no-restart", action="store_true",
                        help="Only pull code, don't restart services")
    opts = parser.parse_args(args)

    config = Config.load(opts.target)
    target = config.current_target

    print_header(f"DEPLOY TO {opts.target.upper()}")
    print(f"  🎯 Server: {target.user}@{target.host}")
    print(f"  📁 Deploy dir: {target.deploy_dir}")

    # Step 1: Ensure local changes are committed and pushed
    print_header("1. PUSH LOCAL CHANGES")
    local = subprocess.run(
        ["git", "status", "--porcelain"],
        capture_output=True, text=True,
        cwd=Path(__file__).parent.parent.parent
    )
    if local.stdout.strip():
        print_step("⚠️ ", "Uncommitted local changes detected:")
        for line in local.stdout.strip().split("\n"):
            print(f"      {line}")
        print_step("❌", "Commit and push before deploying.")
        return 1

    print_step("📤", "Pushing to remote...")
    push = subprocess.run(
        ["git", "push", "origin", "master"],
        capture_output=True, text=True,
        cwd=Path(__file__).parent.parent.parent
    )
    if push.returncode != 0:
        print_step("❌", f"Push failed: {push.stderr.strip()}")
        return 1
    print_step("✅", "Pushed")

    # Step 2: Pull on server
    print_header("2. PULL ON SERVER")
    result = ssh_run(config, f"cd {target.deploy_dir} && git pull", check=False)
    if result.returncode != 0:
        print_step("❌", f"Pull failed: {result.stderr.strip()}")
        return 1
    for line in result.stdout.strip().split("\n"):
        if line.strip():
            print_step("📥", line.strip())

    # Step 3: Sync .env (optional)
    if opts.sync_env:
        from commands.env import cmd_env
        print_header("3. SYNC ENVIRONMENT")
        print_step("📄", "Syncing .env to server...")
        ret = cmd_env(["--target", opts.target, "--no-prompt"])
        if ret != 0:
            print_step("❌", "Env sync failed!")
            return 1
    else:
        print_header("3. SYNC ENVIRONMENT")
        print_step("⏭️ ", "Skipped (use --sync-env to sync)")

    # Step 4: Restart services
    if opts.no_restart:
        print_header("4. RESTART SERVICES")
        print_step("⏭️ ", "Skipped (--no-restart)")
    else:
        print_header("4. RESTART SERVICES")
        compose_dir = f"{target.deploy_dir}/infrastructure/{target.compose_dir}"

        if opts.restart is not None and len(opts.restart) > 0:
            services = " ".join(opts.restart)
            print_step("🔄", f"Restarting: {services}")
            ssh_run(config, f"cd {compose_dir} && docker compose up -d --force-recreate {services}", check=False)
        else:
            print_step("🔄", "Restarting all services...")
            ssh_run(config, f"cd {compose_dir} && docker compose up -d --force-recreate", check=False)

        print_step("⏳", "Waiting for services...")
        ssh_run(config, "sleep 10", check=False)

        result = ssh_run(config, f"cd {compose_dir} && docker compose ps --format 'table {{{{.Name}}}}\\t{{{{.Status}}}}'", check=False)
        for line in result.stdout.strip().split("\n"):
            if line.strip():
                print(f"      {line.strip()}")

    print_header("DEPLOY COMPLETE")
    return 0

