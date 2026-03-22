"""
Clean Command - Clean up server (repo, secrets, volumes).
Certificates are preserved.
"""

import argparse
import subprocess
from typing import List

import sys
from pathlib import Path
CLI_DIR = Path(__file__).parent.parent.resolve()
sys.path.insert(0, str(CLI_DIR))

from utils.config import Config


def print_header(text: str) -> None:
    print(f"\n{'─' * 60}")
    print(f"  {text}")
    print(f"{'─' * 60}")


def print_step(icon: str, text: str) -> None:
    print(f"  {icon}  {text}")


def ssh_run(config, command: str, check: bool = True) -> subprocess.CompletedProcess:
    """Run SSH command on remote server."""
    target = config.current_target
    ssh_cmd = [
        "ssh", "-i", target.ssh_key,
        "-o", "StrictHostKeyChecking=accept-new",
        f"{target.user}@{target.host}",
        command
    ]
    return subprocess.run(ssh_cmd, capture_output=True, text=True, check=check)


def cmd_clean(args: List[str]) -> int:
    """Clean command handler."""
    parser = argparse.ArgumentParser(prog="calcifer-cli.py clean")
    parser.add_argument("--target", choices=["home", "cloud"], default="cloud")
    parser.add_argument("--confirm", action="store_true", help="Skip confirmation prompt")
    opts = parser.parse_args(args)

    config = Config.load(target=opts.target)
    target = config.current_target

    print_header(f"CLEAN SERVER ({opts.target.upper()})")
    print()
    print(f"  🎯 Server: {target.user}@{target.host}")
    print(f"  📁 Deploy dir: {target.deploy_dir}")
    print()
    print("  ⚠️  This will remove:")
    print("      - Repository clone")
    print("      - Environment secrets (.env)")
    print("      - All data volumes")
    print("      - Systemd service")
    print()
    print("  ✅ This will KEEP:")
    print("      - SSL Certificates (/opt/certs)")
    print()

    if not opts.confirm:
        response = input("  Are you sure? Type 'yes' to confirm: ")
        if response.lower() != 'yes':
            print("\n  ❌ Cancelled.\n")
            return 1

    print_header("STOPPING SERVICES")
    print_step("🛑", "Stopping docker compose...")
    ssh_run(config, f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && docker compose down 2>/dev/null || true", check=False)

    print_header("REMOVING DATA")
    
    # Get DATA_DIR from remote .env
    result = ssh_run(config, f"grep DATA_DIR {target.deploy_dir}/infrastructure/{target.compose_dir}/.env 2>/dev/null | cut -d= -f2", check=False)
    data_dir = result.stdout.strip() or f"/var/lib/calcifer/{opts.target}"
    
    print_step("🗑️ ", f"Removing volumes: {data_dir}")
    ssh_run(config, f"sudo rm -rf {data_dir}", check=False)
    
    print_step("🗑️ ", f"Removing .env secrets")
    ssh_run(config, f"rm -f {target.deploy_dir}/infrastructure/{target.compose_dir}/.env", check=False)
    
    print_step("🗑️ ", f"Removing repository: {target.deploy_dir}")
    ssh_run(config, f"rm -rf {target.deploy_dir}", check=False)

    print_header("REMOVING SYSTEMD SERVICE")
    print_step("🗑️ ", "Disabling calcifer service...")
    ssh_run(config, "sudo systemctl stop calcifer 2>/dev/null || true", check=False)
    ssh_run(config, "sudo systemctl disable calcifer 2>/dev/null || true", check=False)
    ssh_run(config, "sudo rm -f /etc/systemd/system/calcifer.service", check=False)
    ssh_run(config, "sudo systemctl daemon-reload", check=False)

    print_header("DONE")
    print_step("✅", "Server cleaned!")
    print()
    print("  Preserved:")
    print("    - /opt/certs (SSL certificates)")
    print()
    print("  To re-bootstrap:")
    print(f"    ./calcifer-cli.py bootstrap --target {opts.target}")
    print()

    return 0

