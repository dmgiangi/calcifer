"""
Docker utilities for container management.
"""

import subprocess
import json
from typing import List, Dict, Any, Optional

from .ssh import ssh_exec
from .config import TargetConfig


def get_container_status_local() -> List[Dict[str, Any]]:
    """Get container status from local Docker."""
    fmt = '{"name":"{{.Names}}","status":"{{.Status}}","state":"{{.State}}"}'
    try:
        result = subprocess.run(
            ["docker", "ps", "-a", "--format", fmt],
            capture_output=True,
            text=True
        )
        if result.returncode == 0 and result.stdout.strip():
            lines = result.stdout.strip().split("\n")
            return [json.loads(line) for line in lines if line]
        return []
    except Exception:
        return []


def get_container_status_remote(target: TargetConfig) -> List[Dict[str, Any]]:
    """Get container status from remote Docker."""
    fmt = '{"name":"{{.Names}}","status":"{{.Status}}","state":"{{.State}}"}'
    result = ssh_exec(target, f'docker ps -a --format \'{fmt}\'')
    
    if result.success and result.stdout.strip():
        try:
            lines = result.stdout.strip().split("\n")
            return [json.loads(line) for line in lines if line]
        except json.JSONDecodeError:
            return []
    return []


def image_exists(image_name: str) -> bool:
    """Check if Docker image exists locally."""
    try:
        result = subprocess.run(
            ["docker", "image", "inspect", image_name],
            capture_output=True
        )
        return result.returncode == 0
    except Exception:
        return False


def get_image_digest(image_name: str) -> Optional[str]:
    """Get Docker image digest."""
    try:
        result = subprocess.run(
            ["docker", "image", "inspect", image_name, 
             "--format", "{{index .RepoDigests 0}}"],
            capture_output=True,
            text=True
        )
        if result.returncode == 0 and "@" in result.stdout:
            return result.stdout.strip().split("@")[1]
        return None
    except Exception:
        return None

