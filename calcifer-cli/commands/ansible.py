"""
Ansible Command - Run Ansible playbooks.

Usage:
    ./calcifer-cli.py ansible <playbook> [--target home|cloud] [--check]
"""

import argparse
import subprocess
import time
from pathlib import Path
from typing import List

from utils import Config, json_success, json_error, log_progress


def register() -> dict:
    return {
        "name": "ansible",
        "description": "Run Ansible playbooks",
        "usage": "./calcifer-cli.py ansible <playbook> [--target home|cloud]",
        "handler": cmd_ansible
    }


def cmd_ansible(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py ansible")
    parser.add_argument("playbook", help="Playbook name (without .yml)")
    parser.add_argument("--target", choices=["home", "cloud"], default="home")
    parser.add_argument("--check", action="store_true", help="Dry run mode")
    parser.add_argument("--tags", default=None, help="Only run specific tags")
    parser.add_argument("--verbose", "-v", action="count", default=0)
    opts = parser.parse_args(args)
    
    config = Config.load(target=opts.target)
    target = config.current_target
    
    # Find playbook
    cli_dir = Path(__file__).parent.parent
    playbooks_dir = cli_dir.parent / "infrastructure" / "ansible" / "playbooks"
    playbook_path = playbooks_dir / f"{opts.playbook}.yml"
    
    if not playbook_path.exists():
        available = [p.stem for p in playbooks_dir.glob("*.yml")]
        print(json_error(
            f"Playbook not found: {opts.playbook}",
            f"Available playbooks: {', '.join(available)}",
            command="ansible"
        ))
        return 1
    
    log_progress(f"Running playbook: {opts.playbook}")
    log_progress(f"Target: {opts.target} ({target.host})")
    
    start = time.time()
    
    # Build ansible command
    ansible_cmd = [
        "ansible-playbook",
        str(playbook_path),
        "-i", f"{target.host},",
        "-u", target.user,
        "--private-key", target.ssh_key,
        "-e", f"deploy_target={opts.target}",
        "-e", f"deploy_dir={target.deploy_dir}",
    ]
    
    if opts.check:
        ansible_cmd.append("--check")
    
    if opts.tags:
        ansible_cmd.extend(["--tags", opts.tags])
    
    if opts.verbose:
        ansible_cmd.append("-" + "v" * opts.verbose)
    
    log_progress(f"Command: {' '.join(ansible_cmd)}")
    
    result = subprocess.run(
        ansible_cmd,
        capture_output=True,
        text=True,
        timeout=600
    )
    
    duration = int(time.time() - start)
    
    data = {
        "playbook": opts.playbook,
        "target": opts.target,
        "server": target.host,
        "check_mode": opts.check,
        "duration_seconds": duration,
        "stdout": result.stdout[-2000:] if result.stdout else "",  # Last 2000 chars
        "return_code": result.returncode
    }
    
    if result.returncode != 0:
        print(json_error(
            f"Playbook {opts.playbook} failed",
            result.stderr[-500:] if result.stderr else "",
            command="ansible",
            data=data
        ))
        return 1
    
    print(json_success("ansible", data, [f"./calcifer-cli.py status --target {opts.target}"], server=target.host))
    return 0

