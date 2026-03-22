"""
Test Command - Run health checks and smoke tests.

Usage:
    ./calcifer-cli.py test [--target home|cloud] [--timeout 30]
"""

import argparse
import time
from typing import List, Dict, Any

from utils import Config, json_success, json_error, log_progress, ssh_exec, ssh_check


# Cloud checks using docker network names (curl via docker exec)
CLOUD_CHECKS = [
    ("grafana", "calcifer_cloud_grafana:3000/api/health", "Grafana Dashboard"),
    ("prometheus", "calcifer_cloud_prometheus:9090/-/healthy", "Prometheus Metrics"),
    ("loki", "calcifer-cloud_loki:3100/ready", "Loki Logs"),
    ("tempo", "calcifer-cloud_tempo:3200/ready", "Tempo Traces"),
    ("keycloak", "calcifer_cloud_keycloak:9000/health/ready", "Keycloak Identity"),
]

HOME_CHECKS = [
    ("core-server", "http://localhost:8080/actuator/health", "Core Server API"),
    ("grafana", "http://localhost:3000/api/health", "Grafana Dashboard"),
    ("prometheus", "http://localhost:9090/-/healthy", "Prometheus Metrics"),
    ("rabbitmq", "http://localhost:15672/api/health/checks/alarms", "RabbitMQ Broker"),
    ("loki", "http://localhost:3100/ready", "Loki Logs"),
]


def register() -> dict:
    return {
        "name": "test",
        "description": "Run health checks and smoke tests",
        "usage": "./calcifer-cli.py test [--target home|cloud] [--timeout 30]",
        "handler": cmd_test
    }


def cmd_test(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py test")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--local", action="store_true")
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    checks = CLOUD_CHECKS if opts.target == "cloud" else HOME_CHECKS
    
    log_progress(f"Running health checks (target: {opts.target}, timeout: {opts.timeout}s)...")
    
    if not opts.local and not ssh_check(target):
        print(json_error(
            f"Cannot connect to remote server {target.host}",
            "Check SSH connectivity",
            command="test"
        ))
        return 1
    
    results: List[Dict[str, Any]] = []
    passed = 0
    failed = 0
    
    for name, url, description in checks:
        log_progress(f"Checking {description}...")
        start = time.time()
        
        # For cloud, use docker exec to curl inside the network
        if opts.target == "cloud" and not opts.local:
            curl_cmd = f"docker exec calcifer_cloud_traefik wget -q -O /dev/null -S --timeout={opts.timeout} 'http://{url}' 2>&1 | grep 'HTTP/' | tail -1 | awk '{{print $2}}' || echo 000"
        else:
            curl_cmd = f"curl -s -o /dev/null -w %{{http_code}} --max-time {opts.timeout} 'http://{url}' 2>/dev/null || echo 000"

        if opts.local:
            import subprocess
            result = subprocess.run(curl_cmd, shell=True, capture_output=True, text=True)
            http_code = result.stdout.strip()
        else:
            result = ssh_exec(target, curl_cmd, timeout=opts.timeout + 5)
            http_code = result.stdout.strip()
        
        response_time = int((time.time() - start) * 1000)
        
        # Clean http_code
        http_code = ''.join(c for c in http_code if c.isdigit())[:3] or "000"
        
        try:
            code_int = int(http_code)
        except ValueError:
            code_int = 0
        
        if code_int == 0:
            status = "unreachable"
            passed_check = False
        elif code_int >= 400:
            status = "unhealthy"
            passed_check = False
        else:
            status = "healthy"
            passed_check = True
        
        if passed_check:
            passed += 1
        else:
            failed += 1
        
        results.append({
            "name": name,
            "description": description,
            "status": status,
            "http_code": code_int,
            "response_time_ms": response_time
        })
    
    # Determine overall status
    if failed == 0:
        overall_status = "healthy"
    elif passed > 0:
        overall_status = "degraded"
    else:
        overall_status = "down"
    
    data = {
        "target": "local" if opts.local else opts.target,
        "overall_status": overall_status,
        "summary": {
            "total": len(checks),
            "passed": passed,
            "failed": failed
        },
        "checks": results
    }
    
    if failed == 0:
        next_actions = [f"./calcifer-cli.py status --target {opts.target}"]
        print(json_success("test", data, next_actions, server=target.host))
        return 0
    else:
        next_actions = [
            f"./calcifer-cli.py logs --target {opts.target}",
            f"./calcifer-cli.py status --target {opts.target}"
        ]
        print(json_error(
            "Some health checks failed",
            "Check logs for failed services",
            command="test",
            data=data
        ))
        return 1

