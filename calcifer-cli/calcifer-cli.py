#!/usr/bin/env python3
"""
Calcifer CLI - Environment configuration and deployment tool.

Usage:
    ./calcifer-cli.py env [--target home|cloud]
"""

import sys
from pathlib import Path

CLI_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(CLI_DIR))

from commands.env import cmd_env


def show_help() -> None:
    """Display help message."""
    print("""
╔═══════════════════════════════════════════════════════════════════╗
║                         CALCIFER CLI                              ║
╚═══════════════════════════════════════════════════════════════════╝

Usage:
    ./calcifer-cli.py env [--target home|cloud]

Description:
    Manages environment configuration for Calcifer deployments.

    - Reads from local .env file (calcifer-cli/.env.{target})
    - If file doesn't exist, generates internal secrets and prompts
      for external ones (Google OAuth credentials)
    - Pushes configuration to the remote server

Options:
    --target    Target environment: home or cloud (default: cloud)

Examples:
    ./calcifer-cli.py env                    # Configure cloud environment
    ./calcifer-cli.py env --target home      # Configure home environment
""")


def main() -> int:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        show_help()
        return 0

    cmd_name = sys.argv[1]

    if cmd_name != "env":
        print(f"\n❌ Unknown command: {cmd_name}")
        print("   Run './calcifer-cli.py --help' for usage.\n")
        return 1

    try:
        return cmd_env(sys.argv[2:])
    except KeyboardInterrupt:
        print("\n\n⚠️  Command interrupted.\n")
        return 130
    except Exception as e:
        print(f"\n❌ Error: {e}\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())

