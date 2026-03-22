"""Tests for main CLI entry point."""

import json
import subprocess
from pathlib import Path


CLI_PATH = Path(__file__).parent.parent / "calcifer-cli.py"


class TestCLI:
    """Tests for main CLI functionality."""
    
    def test_help_command(self):
        """Help command should return valid JSON."""
        result = subprocess.run(
            ["python3", str(CLI_PATH), "help"],
            capture_output=True,
            text=True
        )
        
        assert result.returncode == 0
        data = json.loads(result.stdout)
        assert data["success"] is True
        assert data["command"] == "help"
        assert "available_commands" in data["data"]
    
    def test_unknown_command(self):
        """Unknown command should return error JSON."""
        result = subprocess.run(
            ["python3", str(CLI_PATH), "nonexistent"],
            capture_output=True,
            text=True
        )
        
        assert result.returncode == 1
        data = json.loads(result.stdout)
        assert data["success"] is False
        assert "Unknown command" in data["errors"][0]["message"]
    
    def test_commands_registered(self):
        """All expected commands should be registered."""
        result = subprocess.run(
            ["python3", str(CLI_PATH), "help"],
            capture_output=True,
            text=True
        )
        
        data = json.loads(result.stdout)
        command_names = [cmd["name"] for cmd in data["data"]["available_commands"]]
        
        expected = ["status", "run", "logs", "test", "rollback", "build", "push", "sync-env"]
        for cmd in expected:
            assert cmd in command_names, f"Command {cmd} not registered"

