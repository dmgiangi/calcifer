"""
SSH utilities for remote command execution.
"""

import subprocess
from typing import Tuple, Optional
from dataclasses import dataclass

from .config import TargetConfig


@dataclass
class SSHResult:
    """Result of an SSH command execution."""
    success: bool
    stdout: str
    stderr: str
    return_code: int


def ssh_exec(target: TargetConfig, command: str, timeout: int = 60) -> SSHResult:
    """Execute command on remote server via SSH."""
    ssh_cmd = [
        "ssh",
        "-i", target.ssh_key,
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", f"ConnectTimeout=10",
        f"{target.user}@{target.host}",
        command
    ]
    
    try:
        result = subprocess.run(
            ssh_cmd,
            capture_output=True,
            text=True,
            timeout=timeout
        )
        return SSHResult(
            success=result.returncode == 0,
            stdout=result.stdout,
            stderr=result.stderr,
            return_code=result.returncode
        )
    except subprocess.TimeoutExpired:
        return SSHResult(
            success=False,
            stdout="",
            stderr=f"Command timed out after {timeout}s",
            return_code=-1
        )
    except Exception as e:
        return SSHResult(
            success=False,
            stdout="",
            stderr=str(e),
            return_code=-1
        )


def ssh_check(target: TargetConfig) -> bool:
    """Check SSH connectivity to target."""
    result = ssh_exec(target, "echo ok", timeout=10)
    return result.success and "ok" in result.stdout


def scp_to(target: TargetConfig, local_path: str, remote_path: str) -> bool:
    """Copy file to remote server."""
    scp_cmd = [
        "scp",
        "-i", target.ssh_key,
        "-o", "StrictHostKeyChecking=accept-new",
        local_path,
        f"{target.user}@{target.host}:{remote_path}"
    ]
    
    try:
        result = subprocess.run(scp_cmd, capture_output=True, timeout=60)
        return result.returncode == 0
    except Exception:
        return False

