"""
Build Command - Build Docker images locally.

Usage:
    ./calcifer-cli.py build [component] [--skip-tests] [--tag <tag>]
"""

import argparse
import subprocess
import time
from typing import List

from utils import Config, json_success, json_error, log_progress, get_version_tag


def register() -> dict:
    return {
        "name": "build",
        "description": "Build Docker images locally",
        "usage": "./calcifer-cli.py build [core-server] [--skip-tests]",
        "handler": cmd_build
    }


def cmd_build(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py build")
    parser.add_argument("components", nargs="*", default=["core-server"])
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--tag", default=None)
    opts = parser.parse_args(args)
    
    config = Config.load()
    version_tag = opts.tag or get_version_tag()
    
    log_progress(f"Building components: {', '.join(opts.components)}")
    log_progress(f"Version tag: {version_tag}")
    
    build_results = []
    errors = []
    
    for component in opts.components:
        log_progress(f"Building {component}...")
        start = time.time()
        success = True
        error_msg = ""
        
        if component == "core-server":
            # Build JAR
            mvn_cmd = ["./mvnw", "package", "-DskipTests", "-q"] if opts.skip_tests else ["./mvnw", "verify", "-q"]
            result = subprocess.run(mvn_cmd, cwd="core-server", capture_output=True)
            
            if result.returncode != 0:
                success = False
                error_msg = "Maven build failed"
            else:
                # Build Docker image
                image_name = f"{config.docker_registry}/{config.docker_image_prefix}/core-server:{version_tag}"
                docker_cmd = [
                    "docker", "build",
                    "-t", image_name,
                    "-t", f"{config.docker_registry}/{config.docker_image_prefix}/core-server:latest",
                    "core-server/"
                ]
                result = subprocess.run(docker_cmd, capture_output=True)
                if result.returncode != 0:
                    success = False
                    error_msg = "Docker build failed"
        
        elif component == "infrastructure":
            # Validate compose files
            result = subprocess.run(
                ["docker", "compose", "-f", "infrastructure/docker-compose.yaml", "config", "-q"],
                capture_output=True
            )
            if result.returncode != 0:
                success = False
                error_msg = "Docker Compose validation failed"
        
        duration = int(time.time() - start)
        build_results.append({
            "component": component,
            "success": success,
            "duration_seconds": duration
        })
        
        if not success:
            errors.append({"component": component, "message": error_msg})
    
    data = {
        "version_tag": version_tag,
        "components_built": build_results,
        "images": [f"{config.docker_registry}/{config.docker_image_prefix}/core-server:{version_tag}"]
    }
    
    if errors:
        print(json_error(
            "Build failed for some components",
            "Check the error details",
            command="build",
            data=data
        ))
        return 1
    
    print(json_success("build", data, ["./calcifer-cli.py push", "./calcifer-cli.py run"]))
    return 0

