"""Pytest configuration and fixtures."""

import sys
from pathlib import Path

import pytest

# Add CLI directory to path
CLI_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(CLI_DIR))


@pytest.fixture
def mock_ssh_success(mocker):
    """Mock successful SSH execution."""
    from utils.ssh import SSHResult
    mock = mocker.patch("utils.ssh.ssh_exec")
    mock.return_value = SSHResult(success=True, stdout="ok", stderr="", return_code=0)
    return mock


@pytest.fixture
def mock_ssh_failure(mocker):
    """Mock failed SSH execution."""
    from utils.ssh import SSHResult
    mock = mocker.patch("utils.ssh.ssh_exec")
    mock.return_value = SSHResult(success=False, stdout="", stderr="error", return_code=1)
    return mock


@pytest.fixture
def mock_ssh_check_success(mocker):
    """Mock successful SSH check."""
    mock = mocker.patch("utils.ssh.ssh_check")
    mock.return_value = True
    return mock


@pytest.fixture
def mock_ssh_check_failure(mocker):
    """Mock failed SSH check."""
    mock = mocker.patch("utils.ssh.ssh_check")
    mock.return_value = False
    return mock


@pytest.fixture
def config():
    """Create test configuration."""
    from utils.config import Config
    return Config.load(target="cloud")

