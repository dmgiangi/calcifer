"""
Status Command - Get current state of all services.

Usage:
    ./calcifer-cli.py status [--target home|cloud] [--local]
"""

import argparse
from typing import List

from utils import (
    Config, json_success, json_error, log_progress,
    ssh_exec, ssh_check, get_git_sha, get_git_branch, get_version_tag
)


def register() -> dict:
    """Register this command with the CLI."""
    return {
        "name": "status",
        "description": "Get current state of all services",
        "usage": "./calcifer-cli.py status [--target home|cloud]",
        "handler": cmd_status
    }


def cmd_status(args: List[str]) -> int:
    """Execute status command."""
    parser = argparse.ArgumentParser(prog="calcifer-cli.py status")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--local", action="store_true", help="Check local instead of remote")
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    
    log_progress(f"Checking deployment status for {opts.target}...")
    
    # Check SSH connectivity
    if not opts.local:
        if not ssh_check(target):
            print(json_error(
                f"Cannot connect to remote server {target.host}",
                "Check SSH key and network connectivity",
                command="status"
            ))
            return 1
    
    # Get git info
    git_info = {
        "branch": get_git_branch(),
        "commit": get_git_sha(),
        "local_version": get_version_tag()
    }
    
    # Get remote version and services
    remote_version = "unknown"
    services = []
    
    if not opts.local:
        log_progress("Fetching remote container status...")
        
        # Get deployed version
        ver_result = ssh_exec(target, f"cat {target.deploy_dir}/.deploy-version 2>/dev/null")
        if ver_result.success:
            remote_version = ver_result.stdout.strip() or "unknown"
        
        # Get container status
        compose_cmd = f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && docker compose ps --format json 2>/dev/null"
        result = ssh_exec(target, compose_cmd)
        
        if result.success and result.stdout.strip():
            import json
            try:
                for line in result.stdout.strip().split("\n"):
                    if line.strip():
                        svc = json.loads(line)
                        services.append({
                            "name": svc.get("Service", svc.get("Name", "")),
                            "status": svc.get("State", "unknown"),
                            "health": svc.get("Health", "unknown") or "unknown",
                            "running": svc.get("State") == "running",
                            "ports": svc.get("Ports", "")
                        })
            except json.JSONDecodeError:
                pass
    
    # Calculate summary
    total = len(services)
    running = sum(1 for s in services if s.get("running"))
    healthy = sum(1 for s in services if s.get("health") == "healthy")
    
    # Determine overall status
    if total == 0:
        overall_status = "not_deployed"
        next_actions = ["./calcifer-cli.py run --target " + opts.target]
    elif running == 0:
        overall_status = "down"
        next_actions = ["./calcifer-cli.py run --target " + opts.target, "./calcifer-cli.py logs --target " + opts.target]
    elif running < total:
        overall_status = "degraded"
        next_actions = ["./calcifer-cli.py logs --target " + opts.target]
    else:
        overall_status = "healthy"
        next_actions = ["./calcifer-cli.py test --target " + opts.target]
    
    data = {
        "overall_status": overall_status,
        "deployed_version": remote_version,
        "git": git_info,
        "summary": {
            "total": total,
            "running": running,
            "healthy": healthy
        },
        "services": services
    }
    
    print(json_success("status", data, next_actions, server=target.host))
    return 0

