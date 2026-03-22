"""
Rollback Command - Rollback to previous version.

Usage:
    ./calcifer-cli.py rollback [--target home|cloud] [--confirm] [--to <commit>]
"""

import argparse
import time
from typing import List

from utils import Config, json_success, json_error, log_progress, ssh_exec, ssh_check


def register() -> dict:
    return {
        "name": "rollback",
        "description": "Rollback to previous deployed version",
        "usage": "./calcifer-cli.py rollback [--target home|cloud] --confirm",
        "handler": cmd_rollback
    }


def cmd_rollback(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py rollback")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--confirm", "-y", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--to", dest="target_version", default=None)
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    
    log_progress(f"Preparing rollback for {opts.target}...")
    
    if not ssh_check(target):
        print(json_error(
            f"Cannot connect to remote server {target.host}",
            "Check SSH connectivity",
            command="rollback"
        ))
        return 1
    
    # Get current and previous versions
    current_result = ssh_exec(target, f"cd {target.deploy_dir} && git rev-parse --short HEAD")
    current_version = current_result.stdout.strip() if current_result.success else "unknown"
    
    prev_result = ssh_exec(target, f"cat {target.deploy_dir}/.deploy-version.previous")
    previous_version = prev_result.stdout.strip() if prev_result.success else "none"
    
    target_version = opts.target_version or previous_version
    
    if target_version in ("none", "unknown", ""):
        print(json_error(
            "No previous version available for rollback",
            "Deploy a version first, then try again",
            command="rollback"
        ))
        return 1
    
    data = {
        "current_version": current_version,
        "target_version": target_version,
        "target_env": opts.target,
        "dry_run": opts.dry_run
    }
    
    if opts.dry_run:
        log_progress(f"DRY RUN - would rollback from {current_version} to {target_version}")
        print(json_success("rollback", data, [f"./calcifer-cli.py rollback --target {opts.target} --confirm"], server=target.host))
        return 0
    
    if not opts.confirm:
        print(json_error(
            "Rollback requires confirmation",
            "Use --confirm or -y flag",
            command="rollback",
            data=data
        ))
        return 1
    
    log_progress(f"Rolling back from {current_version} to {target_version}...")
    start_time = time.time()
    
    # Save current as previous for potential re-rollback
    ssh_exec(target, f"echo '{current_version}' > {target.deploy_dir}/.deploy-version.previous")
    
    # Git checkout
    log_progress(f"Checking out {target_version}...")
    result = ssh_exec(target, f"cd {target.deploy_dir} && git checkout {target_version}")
    if not result.success:
        print(json_error(f"Failed to checkout version {target_version}", result.stderr, command="rollback"))
        return 1
    
    # Restart services
    log_progress(f"Restarting {opts.target} services...")
    compose_dir = f"{target.deploy_dir}/infrastructure/{target.compose_dir}"
    result = ssh_exec(target, f"cd {compose_dir} && docker compose up -d --remove-orphans", timeout=180)
    if not result.success:
        print(json_error("Failed to restart services", result.stderr, command="rollback"))
        return 1
    
    # Update version file
    ssh_exec(target, f"echo '{target_version}' > {target.deploy_dir}/.deploy-version")
    
    duration = int(time.time() - start_time)
    
    data = {
        "previous_version": current_version,
        "rolled_back_to": target_version,
        "target_env": opts.target,
        "duration_seconds": duration
    }
    
    next_actions = [
        f"./calcifer-cli.py test --target {opts.target}",
        f"./calcifer-cli.py status --target {opts.target}"
    ]
    
    print(json_success("rollback", data, next_actions, server=target.host))
    return 0

