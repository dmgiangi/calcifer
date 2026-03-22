"""Tests for utility modules."""

import json
import pytest
from utils.output import json_success, json_error, timestamp
from utils.config import Config, TargetConfig
from utils.git import get_git_sha, get_git_branch


class TestOutput:
    """Tests for output formatting."""
    
    def test_timestamp_format(self):
        """Timestamp should be ISO8601 format."""
        ts = timestamp()
        assert ts.endswith("Z")
        assert "T" in ts
        assert len(ts) == 20  # YYYY-MM-DDTHH:MM:SSZ
    
    def test_json_success_structure(self):
        """Success response should have required fields."""
        result = json_success("test_cmd", {"key": "value"}, ["next_action"])
        data = json.loads(result)
        
        assert data["success"] is True
        assert data["command"] == "test_cmd"
        assert data["data"]["key"] == "value"
        assert data["errors"] == []
        assert "next_action" in data["next_actions"]
        assert "timestamp" in data
    
    def test_json_error_structure(self):
        """Error response should have required fields."""
        result = json_error("Error message", "Hint text", command="test_cmd")
        data = json.loads(result)
        
        assert data["success"] is False
        assert data["command"] == "test_cmd"
        assert len(data["errors"]) == 1
        assert data["errors"][0]["message"] == "Error message"
        assert data["errors"][0]["hint"] == "Hint text"


class TestConfig:
    """Tests for configuration management."""
    
    def test_default_config(self):
        """Default config should have home and cloud targets."""
        config = Config()
        
        assert config.target == "home"
        assert isinstance(config.home, TargetConfig)
        assert isinstance(config.cloud, TargetConfig)
    
    def test_current_target_home(self):
        """current_target should return home config when target=home."""
        config = Config()
        config.target = "home"
        
        assert config.current_target == config.home
    
    def test_current_target_cloud(self):
        """current_target should return cloud config when target=cloud."""
        config = Config()
        config.target = "cloud"
        
        assert config.current_target == config.cloud
    
    def test_load_with_target(self):
        """load() should accept target parameter."""
        config = Config.load(target="cloud")
        
        assert config.target == "cloud"


class TestGit:
    """Tests for git utilities."""
    
    def test_get_git_sha_format(self):
        """Git SHA should be short format."""
        sha = get_git_sha()
        # Either 'unknown' or a short SHA (7-8 chars)
        assert sha == "unknown" or (len(sha) >= 7 and len(sha) <= 8)
    
    def test_get_git_branch(self):
        """Git branch should return a string."""
        branch = get_git_branch()
        assert isinstance(branch, str)
        assert len(branch) > 0

