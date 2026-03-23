"""
Configuration management for Calcifer CLI.

Loads configuration from:
1. Default values
2. deploy.conf file
3. Environment variables
"""

import os
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class TargetConfig:
    """Configuration for a deployment target (home/cloud)."""
    host: str
    user: str
    ssh_key: str
    deploy_dir: str
    compose_dir: str


@dataclass 
class Config:
    """Global CLI configuration."""
    # General
    environment: str = "production"
    target: str = "home"
    
    # Git
    git_repo_url: str = "git@github.com:dmgiangi/calcifer.git"
    git_branch: str = "master"
    
    # Docker
    docker_registry: str = "ghcr.io"
    docker_image_prefix: str = "calcifer"
    
    # Targets
    home: TargetConfig = field(default_factory=lambda: TargetConfig(
        host="192.168.8.180",
        user="dmgiangi", 
        ssh_key=str(Path.home() / ".ssh/github_id"),
        deploy_dir="/opt/calcifer",
        compose_dir="home"
    ))
    
    cloud: TargetConfig = field(default_factory=lambda: TargetConfig(
        host="dmgiangi.dev",
        user="dmgiangi",
        ssh_key=str(Path.home() / ".ssh/github_id"),
        deploy_dir="/opt/calcifer",
        compose_dir="cloud"
    ))
    
    @property
    def current_target(self) -> TargetConfig:
        """Get configuration for current target."""
        return self.cloud if self.target == "cloud" else self.home
    
    @classmethod
    def load(cls, target: Optional[str] = None) -> "Config":
        """Load configuration from file and environment."""
        config = cls()
        
        # Load from deploy.conf if exists (in calcifer-cli/)
        conf_path = Path(__file__).parent.parent / "deploy.conf"
        if conf_path.exists():
            config._load_conf_file(conf_path)
        
        # Override from environment
        config._load_env()
        
        # Set target if provided
        if target:
            config.target = target
        
        return config
    
    def _load_conf_file(self, path: Path) -> None:
        """Parse deploy.conf file."""
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, value = line.split("=", 1)
                    value = value.strip().strip('"\'')
                    # Expand environment variables like ${HOME}
                    value = os.path.expandvars(value)
                    self._set_from_key(key.strip(), value)
    
    def _load_env(self) -> None:
        """Load configuration from environment variables."""
        env_mappings = {
            "DEPLOY_ENV": "environment",
            "DEPLOY_TARGET": "target",
            "GIT_REPO_URL": "git_repo_url",
            "GIT_BRANCH": "git_branch",
            "DOCKER_REGISTRY": "docker_registry",
            "HOME_HOST": ("home", "host"),
            "HOME_USER": ("home", "user"),
            "HOME_SSH_KEY": ("home", "ssh_key"),
            "CLOUD_HOST": ("cloud", "host"),
            "CLOUD_USER": ("cloud", "user"),
            "CLOUD_SSH_KEY": ("cloud", "ssh_key"),
        }
        
        for env_key, attr in env_mappings.items():
            value = os.environ.get(env_key)
            if value:
                if isinstance(attr, tuple):
                    target, key = attr
                    setattr(getattr(self, target), key, value)
                else:
                    setattr(self, attr, value)
    
    def _set_from_key(self, key: str, value: str) -> None:
        """Set config value from deploy.conf key."""
        mappings = {
            "HOME_HOST": ("home", "host"),
            "HOME_USER": ("home", "user"),
            "HOME_SSH_KEY": ("home", "ssh_key"),
            "HOME_DEPLOY_DIR": ("home", "deploy_dir"),
            "CLOUD_HOST": ("cloud", "host"),
            "CLOUD_USER": ("cloud", "user"),
            "CLOUD_SSH_KEY": ("cloud", "ssh_key"),
            "CLOUD_DEPLOY_DIR": ("cloud", "deploy_dir"),
        }
        
        if key in mappings:
            target, attr = mappings[key]
            setattr(getattr(self, target), attr, value)

