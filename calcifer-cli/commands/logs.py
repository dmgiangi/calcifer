"""
Logs Command - Fetch logs from services.

Usage:
    ./calcifer-cli.py logs [service] [--target home|cloud] [--lines 50]
"""

import argparse
from typing import List

from utils import Config, json_success, json_error, log_progress, ssh_exec, ssh_check


SERVICE_MAP = {
    "core-server": "calcifer_core_server",
    "grafana": "calcifer_grafana",
    "prometheus": "calcifer_prometheus",
    "rabbitmq": "calcifer_rabbitmq",
    "redis": "calcifer_redis",
    "mongodb": "calcifer_mongodb",
    "loki": "calcifer_loki",
    "tempo": "calcifer_tempo",
    "keycloak": "calcifer_keycloak",
    "traefik": "calcifer_traefik",
    "forward-auth": "calcifer_forward_auth",
}


def register() -> dict:
    return {
        "name": "logs",
        "description": "Fetch logs from services",
        "usage": "./calcifer-cli.py logs [service] [--target home|cloud] [-n 50]",
        "handler": cmd_logs
    }


def cmd_logs(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py logs")
    parser.add_argument("service", nargs="?", default=None, help="Service name")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("-n", "--lines", type=int, default=50)
    parser.add_argument("--since", default=None, help="Show logs since timestamp")
    parser.add_argument("--local", action="store_true")
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    
    log_progress(f"Fetching logs (service: {opts.service or 'all'}, lines: {opts.lines})...")
    
    if not opts.local:
        if not ssh_check(target):
            print(json_error(
                f"Cannot connect to remote server {target.host}",
                "Check SSH connectivity",
                command="logs"
            ))
            return 1
    
    # Build docker logs command
    docker_cmd = f"docker logs --tail {opts.lines}"
    if opts.since:
        docker_cmd += f" --since {opts.since}"
    
    logs_output = ""
    
    if opts.service:
        container = SERVICE_MAP.get(opts.service, opts.service)
        # Add target prefix for cloud
        if opts.target == "cloud":
            container = container.replace("calcifer_", "calcifer_cloud_")
        
        if opts.local:
            import subprocess
            result = subprocess.run(
                f"{docker_cmd} {container}",
                shell=True, capture_output=True, text=True
            )
            logs_output = result.stdout + result.stderr
        else:
            result = ssh_exec(target, f"{docker_cmd} {container} 2>&1")
            logs_output = result.stdout
    else:
        compose_dir = f"{target.deploy_dir}/infrastructure/{target.compose_dir}"
        compose_cmd = f"cd {compose_dir} && docker compose logs --tail {opts.lines}"
        if opts.since:
            compose_cmd += f" --since {opts.since}"
        
        result = ssh_exec(target, f"{compose_cmd} 2>&1", timeout=30)
        logs_output = result.stdout
    
    # Limit output
    lines = logs_output.split("\n")[:opts.lines]
    logs_output = "\n".join(lines)
    
    data = {
        "service": opts.service or "all",
        "target": "local" if opts.local else opts.target,
        "lines_requested": opts.lines,
        "logs": logs_output
    }
    
    print(json_success("logs", data, ["./calcifer-cli.py status", "./calcifer-cli.py test"], server=target.host))
    return 0

