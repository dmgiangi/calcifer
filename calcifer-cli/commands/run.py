"""
Run Command - Deploy to remote server via Git.

Usage:
    ./calcifer-cli.py run [--target home|cloud] [--branch main] [--dry-run]
"""

import argparse
import time
from typing import List

from utils import (
    Config, json_success, json_error, log_progress,
    ssh_exec, ssh_check, get_version_tag
)


def register() -> dict:
    return {
        "name": "run",
        "description": "Deploy to remote server via Git pull",
        "usage": "./calcifer-cli.py run [--target home|cloud] [--branch master]",
        "handler": cmd_run
    }


def cmd_run(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py run")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--branch", default="master")
    parser.add_argument("--tag", default=None, help="Specific git tag/commit to deploy")
    parser.add_argument("--dry-run", action="store_true")
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    version_tag = opts.tag or get_version_tag()
    
    log_progress(f"Deploying version: {version_tag}")
    log_progress(f"Target: {opts.target} ({target.host})")
    log_progress(f"Branch: {opts.branch}")
    
    if not ssh_check(target):
        print(json_error(
            f"Cannot connect to remote server {target.host}",
            "Check SSH key and network connectivity",
            command="run"
        ))
        return 1
    
    start_time = time.time()
    
    # Check if repo exists
    repo_check = ssh_exec(target, f"[ -d {target.deploy_dir}/.git ] && echo yes || echo no")
    repo_exists = "yes" in repo_check.stdout
    
    # Get previous version
    prev_result = ssh_exec(target, f"cat {target.deploy_dir}/.deploy-version 2>/dev/null")
    previous_version = prev_result.stdout.strip() if prev_result.success else "none"
    
    if opts.dry_run:
        log_progress(f"DRY RUN - would deploy {version_tag} (previous: {previous_version})")
        data = {
            "dry_run": True,
            "version_tag": version_tag,
            "previous_version": previous_version,
            "target_server": target.host,
            "target_env": opts.target,
            "repo_exists": repo_exists,
            "branch": opts.branch
        }
        print(json_success("run", data, [f"./calcifer-cli.py run --target {opts.target}"], server=target.host))
        return 0
    
    # Step 1: Clone or pull
    if not repo_exists:
        log_progress(f"Cloning repository to {target.deploy_dir}...")
        result = ssh_exec(target, f"git clone --branch {opts.branch} {config.git_repo_url} {target.deploy_dir}", timeout=120)
        if not result.success:
            print(json_error("Failed to clone repository", result.stderr, command="run"))
            return 1
    else:
        log_progress("Pulling latest changes...")
        ssh_exec(target, f"cd {target.deploy_dir} && echo $(git rev-parse --short HEAD) > .deploy-version.previous")
        result = ssh_exec(target, f"cd {target.deploy_dir} && git fetch origin && git checkout {opts.branch} && git pull origin {opts.branch}", timeout=120)
        if not result.success:
            print(json_error("Failed to pull latest changes", result.stderr, command="run"))
            return 1
    
    # Step 2: Create data directories
    log_progress("Creating data directories...")
    data_dir = f"/var/lib/calcifer/{opts.target}"
    dirs_cmd = f"sudo mkdir -p {data_dir}/{{prometheus,grafana,keycloak,traefik/certs,loki,tempo}} && sudo chown -R $(id -u):$(id -g) {data_dir} 2>/dev/null || true"
    ssh_exec(target, dirs_cmd)
    
    # Step 3: Docker compose up
    log_progress(f"Starting {opts.target} services...")
    compose_dir = f"{target.deploy_dir}/infrastructure/{target.compose_dir}"
    result = ssh_exec(target, f"cd {compose_dir} && docker compose pull && docker compose up -d --remove-orphans", timeout=300)
    
    if not result.success:
        print(json_error("Docker Compose deployment failed", result.stderr, command="run"))
        return 1
    
    # Save deployed version
    ssh_exec(target, f"cd {target.deploy_dir} && echo $(git rev-parse --short HEAD) > .deploy-version")
    
    # Get actual deployed commit
    deployed_result = ssh_exec(target, f"cd {target.deploy_dir} && git rev-parse --short HEAD")
    deployed_commit = deployed_result.stdout.strip() if deployed_result.success else "unknown"
    
    duration = int(time.time() - start_time)
    
    data = {
        "version_tag": deployed_commit,
        "previous_version": previous_version,
        "target_server": target.host,
        "target_env": opts.target,
        "branch": opts.branch,
        "deploy_method": "git-pull",
        "duration_seconds": duration
    }
    
    next_actions = [
        f"./calcifer-cli.py test --target {opts.target}",
        f"./calcifer-cli.py status --target {opts.target}"
    ]
    
    print(json_success("run", data, next_actions, server=target.host))
    return 0

