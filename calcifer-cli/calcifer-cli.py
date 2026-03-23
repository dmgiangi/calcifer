#!/usr/bin/env python3
"""
Calcifer CLI - Server management and deployment tool.
"""

import sys
from pathlib import Path

CLI_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(CLI_DIR))

from commands.env import cmd_env
from commands.bootstrap import cmd_bootstrap
from commands.clean import cmd_clean
from commands.deploy import cmd_deploy


COMMANDS = {
    "env": (cmd_env, "Configure environment variables and secrets"),
    "bootstrap": (cmd_bootstrap, "Bootstrap remote server from scratch"),
    "deploy": (cmd_deploy, "Deploy changes without full clean+bootstrap"),
    "clean": (cmd_clean, "Clean server (remove repo, volumes, secrets)"),
}


def show_help() -> None:
    """Display help message."""
    print("""
╔═══════════════════════════════════════════════════════════════════╗
║                         CALCIFER CLI                              ║
╚═══════════════════════════════════════════════════════════════════╝

Usage:
    ./calcifer-cli.py <command> [--target home|cloud] [options]

Commands:
    bootstrap   Bootstrap remote server from scratch
    deploy      Deploy changes without full clean+bootstrap
    env         Configure environment variables and secrets
    clean       Clean server (remove repo, volumes, secrets)

Options:
    --target    Target environment: home or cloud (default: cloud)
    --help      Show help for a specific command

Examples:
    ./calcifer-cli.py bootstrap --target cloud            # Full setup (prompts for secrets)
    ./calcifer-cli.py deploy --target cloud               # Push + pull + restart all
    ./calcifer-cli.py deploy --restart traefik grafana     # Restart specific services
    ./calcifer-cli.py deploy --sync-env                   # Also sync .env
    ./calcifer-cli.py deploy --no-restart                 # Only pull code
    ./calcifer-cli.py clean --target cloud                # Wipe server (keep certs)

Bootstrap workflow:
    1. ./calcifer-cli.py bootstrap --target cloud
       - Clones repo, creates folders, configures env
       - Creates systemd service, starts services
       - Keycloak imports realm config with Google IDP at startup

    2. Access services:
       - Keycloak Admin: https://keycloak.dmgiangi.dev/admin/
       - App services: login with Google via forward-auth
""")


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        show_help()
        return 0

    cmd_name = sys.argv[1]

    if cmd_name not in COMMANDS:
        print(f"\n❌ Unknown command: {cmd_name}")
        print(f"   Available: {', '.join(COMMANDS.keys())}")
        print("   Run './calcifer-cli.py --help' for usage.\n")
        return 1

    cmd_func, _ = COMMANDS[cmd_name]

    try:
        return cmd_func(sys.argv[2:])
    except KeyboardInterrupt:
        print("\n\n⚠️  Command interrupted.\n")
        return 130
    except Exception as e:
        print(f"\n❌ Error: {e}\n")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())

