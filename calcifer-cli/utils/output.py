"""
JSON output formatting for LLM-friendly responses.

All CLI output uses these functions to ensure consistent, parseable JSON.
"""

import json
import sys
from datetime import datetime, timezone
from typing import Any, List, Optional


def timestamp() -> str:
    """Get current timestamp in ISO8601 format."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def json_success(
    command: str,
    data: Any,
    next_actions: Optional[List[str]] = None,
    environment: str = "production",
    server: str = ""
) -> str:
    """Format successful response as JSON."""
    response = {
        "timestamp": timestamp(),
        "command": command,
        "success": True,
        "environment": environment,
        "server": server,
        "data": data,
        "errors": [],
        "next_actions": next_actions or []
    }
    return json.dumps(response, indent=2)


def json_error(
    message: str,
    hint: str = "",
    command: str = "unknown",
    environment: str = "production",
    server: str = "",
    data: Optional[Any] = None
) -> str:
    """Format error response as JSON."""
    response = {
        "timestamp": timestamp(),
        "command": command,
        "success": False,
        "environment": environment,
        "server": server,
        "data": data or {},
        "errors": [
            {
                "message": message,
                "hint": hint
            }
        ],
        "next_actions": ["./calcifer-cli.py help", "./calcifer-cli.py status"]
    }
    return json.dumps(response, indent=2)


def log_progress(message: str) -> None:
    """Log progress message to stderr (doesn't interfere with JSON output)."""
    timestamp_str = datetime.now().strftime("%H:%M:%S")
    print(f"[{timestamp_str}] {message}", file=sys.stderr)

