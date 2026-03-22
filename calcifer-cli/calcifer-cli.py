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
from commands.init_admin import cmd_init_admin


COMMANDS = {
    "env": (cmd_env, "Configure environment variables and secrets"),
    "bootstrap": (cmd_bootstrap, "Bootstrap remote server from scratch"),
    "clean": (cmd_clean, "Clean server (remove repo, volumes, secrets)"),
    "init-admin": (cmd_init_admin, "Assign admin role after first Google login"),
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
    init-admin  Assign admin role after first Google login
    env         Configure environment variables and secrets
    clean       Clean server (remove repo, volumes, secrets)

Options:
    --target    Target environment: home or cloud (default: cloud)
    --help      Show help for a specific command

Examples:
    ./calcifer-cli.py bootstrap --target cloud   # Full server setup
    ./calcifer-cli.py init-admin --target cloud  # Assign admin after login
    ./calcifer-cli.py clean --target cloud       # Wipe server (keep certs)

Bootstrap workflow:
    1. ./calcifer-cli.py bootstrap --target cloud
       - Clones repo, creates folders, configures env
       - Creates systemd service, starts services
       - Configures Keycloak Google IDP

    2. Login to Keycloak admin with Google:
       https://keycloak.dmgiangi.dev/admin/master/console/

    3. ./calcifer-cli.py init-admin --target cloud
       - Assigns admin role to your Google account
       - Disables temporary bootstrap admin
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

