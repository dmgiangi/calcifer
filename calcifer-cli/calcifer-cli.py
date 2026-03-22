#!/usr/bin/env python3
"""
Calcifer CLI - LLM-friendly deployment and management tool.

Usage:
    ./calcifer-cli.py <command> [options]

Commands are loaded dynamically from the commands/ directory.
All output is JSON-formatted for easy parsing by LLMs.
"""

import sys
import importlib
from pathlib import Path

# Add cli directory to path
CLI_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(CLI_DIR))

from utils.output import json_error


def load_commands() -> dict:
    """Dynamically load all command modules from commands/."""
    commands = {}
    commands_dir = CLI_DIR / "commands"
    
    for cmd_file in commands_dir.glob("*.py"):
        if cmd_file.name.startswith("_"):
            continue
        
        module_name = cmd_file.stem
        try:
            module = importlib.import_module(f"commands.{module_name}")
            if hasattr(module, "register"):
                cmd_info = module.register()
                commands[cmd_info["name"]] = cmd_info
        except Exception as e:
            # Skip broken modules silently in production
            pass
    
    return commands


def show_help(commands: dict) -> None:
    """Display help message with available commands."""
    help_data = {
        "available_commands": [
            {
                "name": name,
                "description": info.get("description", ""),
                "usage": info.get("usage", f"./calcifer-cli.py {name}")
            }
            for name, info in sorted(commands.items())
        ],
        "global_options": [
            {"flag": "--target", "description": "Target environment (home|cloud)"},
            {"flag": "--json", "description": "Force JSON output (default)"},
            {"flag": "--help, -h", "description": "Show this help message"},
        ]
    }
    
    from utils.output import json_success
    print(json_success("help", help_data, ["./calcifer-cli.py status"]))


def main() -> int:
    commands = load_commands()
    
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        show_help(commands)
        return 0
    
    cmd_name = sys.argv[1]
    
    if cmd_name not in commands:
        print(json_error(
            f"Unknown command: {cmd_name}",
            f"Available commands: {', '.join(sorted(commands.keys()))}"
        ))
        return 1
    
    # Execute command
    cmd_info = commands[cmd_name]
    cmd_func = cmd_info["handler"]
    
    try:
        return cmd_func(sys.argv[2:])
    except KeyboardInterrupt:
        print(json_error("Command interrupted", "Use Ctrl+C again to exit"))
        return 130
    except Exception as e:
        print(json_error(str(e), "Check logs for details"))
        return 1


if __name__ == "__main__":
    sys.exit(main())

