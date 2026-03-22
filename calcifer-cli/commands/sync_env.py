"""
Sync-Env Command - Copy .env files to remote server.

Usage:
    ./calcifer-cli.py sync-env [--target home|cloud] [--dry-run]
"""

import argparse
import time
from pathlib import Path
from typing import List

from utils import Config, json_success, json_error, log_progress, ssh_exec, ssh_check, scp_to


def register() -> dict:
    return {
        "name": "sync-env",
        "description": "Copy .env files to remote server",
        "usage": "./calcifer-cli.py sync-env [--target home|cloud]",
        "handler": cmd_sync_env
    }


def cmd_sync_env(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py sync-env")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--dry-run", action="store_true")
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    
    log_progress(f"Syncing .env files to {opts.target} ({target.host})...")
    
    if not ssh_check(target):
        print(json_error(
            f"Cannot connect to remote server {target.host}",
            "Check SSH connectivity",
            command="sync-env"
        ))
        return 1
    
    # Find .env files
    cli_dir = Path(__file__).parent.parent
    infra_dir = cli_dir.parent / "infrastructure"
    
    env_files = []
    if opts.target == "cloud":
        env_files.append(infra_dir / "cloud" / ".env")
    else:
        env_files.append(infra_dir / "home" / ".env")
    
    # Check for missing files
    missing = [str(f) for f in env_files if not f.exists()]
    if missing:
        print(json_error(
            f"Missing .env files: {missing}",
            "Create the .env files locally first",
            command="sync-env"
        ))
        return 1
    
    if opts.dry_run:
        data = {
            "dry_run": True,
            "target": opts.target,
            "files_to_sync": [str(f.relative_to(infra_dir)) for f in env_files]
        }
        print(json_success("sync-env", data, [f"./calcifer-cli.py sync-env --target {opts.target}"], server=target.host))
        return 0
    
    start = time.time()
    synced = []
    
    # Ensure remote directory exists
    ssh_exec(target, f"mkdir -p {target.deploy_dir}/infrastructure/{target.compose_dir}")
    
    for env_file in env_files:
        relative = env_file.relative_to(infra_dir)
        remote_path = f"{target.deploy_dir}/infrastructure/{relative}"
        
        log_progress(f"Copying {relative}...")
        
        if scp_to(target, str(env_file), remote_path):
            synced.append(str(relative))
        else:
            print(json_error(f"Failed to copy {relative}", "", command="sync-env"))
            return 1
    
    duration = int(time.time() - start)
    
    data = {
        "target": opts.target,
        "server": target.host,
        "synced_files": synced,
        "duration_seconds": duration
    }
    
    print(json_success("sync-env", data, [f"./calcifer-cli.py run --target {opts.target}"], server=target.host))
    return 0

