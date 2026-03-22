"""
Init Admin Command - Assign admin role to Google user after first login.

This command should be run after:
1. Bootstrap completes
2. User logs into Keycloak admin console with Google for the first time
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


def cmd_init_admin(args: List[str]) -> int:
    """Init admin command handler."""
    parser = argparse.ArgumentParser(prog="calcifer-cli.py init-admin")
    parser.add_argument("--target", choices=["home", "cloud"], default="cloud")
    opts = parser.parse_args(args)

    if opts.target != "cloud":
        print("\n  ⚠️  init-admin is only needed for cloud target.\n")
        return 0

    config = Config.load(target=opts.target)
    target = config.current_target

    print_header("ASSIGN ADMIN ROLE")
    print()
    print("  This command assigns the admin role to your Google account")
    print("  after you've logged into Keycloak for the first time.")
    print()

    # Run keycloak-init
    print_step("🔧", "Running keycloak-init...")
    
    result = ssh_run(config,
        f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && "
        "docker compose up -d --force-recreate keycloak-init 2>&1",
        check=False)
    
    # Wait for init to complete
    ssh_run(config, "sleep 15", check=False)
    
    # Get logs
    result = ssh_run(config, "docker logs calcifer_cloud_keycloak_init 2>&1", check=False)
    
    print()
    admin_assigned = False
    bootstrap_disabled = False
    
    for line in result.stdout.strip().split('\n'):
        if '[INIT]' in line:
            # Highlight important lines
            if 'admin role assigned' in line.lower():
                print_step("✅", line.split('[INIT]')[1].strip())
                admin_assigned = True
            elif 'bootstrap admin' in line.lower() and 'disabled' in line.lower():
                print_step("✅", line.split('[INIT]')[1].strip())
                bootstrap_disabled = True
            elif 'error' in line.lower():
                print_step("❌", line.split('[INIT]')[1].strip())
            elif 'not found' in line.lower() or 'hasn\'t logged in' in line.lower():
                print_step("⚠️ ", line.split('[INIT]')[1].strip())
            else:
                print_step("📝", line.split('[INIT]')[1].strip())

    print_header("RESULT")
    
    if admin_assigned:
        print()
        print("  ✅ Admin role assigned successfully!")
        print()
        print("  You can now access the Keycloak admin console:")
        print("      https://keycloak.dmgiangi.dev/admin/master/console/")
        print()
        if bootstrap_disabled:
            print("  🔒 Bootstrap admin account has been disabled.")
            print()
    else:
        print()
        print("  ⚠️  Admin role not assigned.")
        print()
        print("  Make sure you have logged into Keycloak first:")
        print("      1. Go to https://keycloak.dmgiangi.dev/admin/master/console/")
        print("      2. Click 'Sign in with Google'")
        print("      3. Complete the Google login")
        print("      4. Run this command again")
        print()

    return 0

