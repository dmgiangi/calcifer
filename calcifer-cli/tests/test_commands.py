"""Tests for CLI commands."""

import json
import pytest
from io import StringIO
from unittest.mock import patch, MagicMock

import sys
from pathlib import Path
CLI_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(CLI_DIR))


class TestStatusCommand:
    """Tests for status command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.status import register
        info = register()
        
        assert info["name"] == "status"
        assert "handler" in info
        assert callable(info["handler"])
    
    def test_status_ssh_failure(self, mock_ssh_check_failure, capsys):
        """Status should fail gracefully when SSH fails."""
        from commands.status import cmd_status
        
        result = cmd_status(["--target", "cloud"])
        captured = capsys.readouterr()
        
        assert result == 1
        data = json.loads(captured.out)
        assert data["success"] is False
        assert "Cannot connect" in data["errors"][0]["message"]


class TestRunCommand:
    """Tests for run command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.run import register
        info = register()
        
        assert info["name"] == "run"
        assert "handler" in info
    
    def test_run_dry_run(self, mock_ssh_check_success, mocker, capsys):
        """Dry run should not execute deployment."""
        from commands.run import cmd_run
        from utils.ssh import SSHResult
        
        mock_exec = mocker.patch("commands.run.ssh_exec")
        mock_exec.return_value = SSHResult(True, "yes", "", 0)
        
        result = cmd_run(["--target", "cloud", "--dry-run"])
        captured = capsys.readouterr()
        
        assert result == 0
        data = json.loads(captured.out)
        assert data["success"] is True
        assert data["data"]["dry_run"] is True


class TestLogsCommand:
    """Tests for logs command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.logs import register
        info = register()
        
        assert info["name"] == "logs"


class TestTestCommand:
    """Tests for test command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.test import register
        info = register()
        
        assert info["name"] == "test"


class TestRollbackCommand:
    """Tests for rollback command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.rollback import register
        info = register()
        
        assert info["name"] == "rollback"
    
    def test_rollback_requires_confirm(self, mock_ssh_check_success, mocker, capsys):
        """Rollback should require confirmation."""
        from commands.rollback import cmd_rollback
        from utils.ssh import SSHResult
        
        mock_exec = mocker.patch("commands.rollback.ssh_exec")
        mock_exec.return_value = SSHResult(True, "abc123", "", 0)
        
        result = cmd_rollback(["--target", "cloud"])
        captured = capsys.readouterr()
        
        assert result == 1
        data = json.loads(captured.out)
        assert "confirmation" in data["errors"][0]["message"].lower()


class TestBuildCommand:
    """Tests for build command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.build import register
        info = register()
        
        assert info["name"] == "build"


class TestPushCommand:
    """Tests for push command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.push import register
        info = register()
        
        assert info["name"] == "push"


class TestSyncEnvCommand:
    """Tests for sync-env command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.sync_env import register
        info = register()
        
        assert info["name"] == "sync-env"


class TestAnsibleCommand:
    """Tests for ansible command."""
    
    def test_register(self):
        """Command should register correctly."""
        from commands.ansible import register
        info = register()
        
        assert info["name"] == "ansible"

