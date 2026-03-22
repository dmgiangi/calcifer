"""
Push Command - Push Docker images to registry.

Usage:
    ./calcifer-cli.py push [--tag <tag>]
"""

import argparse
import subprocess
import time
from typing import List

from utils import Config, json_success, json_error, log_progress, get_version_tag
from utils.docker import image_exists, get_image_digest


def register() -> dict:
    return {
        "name": "push",
        "description": "Push Docker images to registry",
        "usage": "./calcifer-cli.py push [--tag <tag>]",
        "handler": cmd_push
    }


def cmd_push(args: List[str]) -> int:
    parser = argparse.ArgumentParser(prog="calcifer-cli.py push")
    parser.add_argument("--tag", default=None)
    opts = parser.parse_args(args)
    
    config = Config.load()
    version_tag = opts.tag or get_version_tag()
    
    log_progress(f"Pushing images with tag: {version_tag}")
    
    image_name = f"{config.docker_registry}/{config.docker_image_prefix}/core-server:{version_tag}"
    
    if not image_exists(image_name):
        print(json_error(
            f"Image not found: {image_name}",
            "Run './calcifer-cli.py build' first",
            command="push"
        ))
        return 1
    
    # Check registry login
    log_progress("Checking registry authentication...")
    result = subprocess.run(
        ["docker", "login", config.docker_registry, "--get-login"],
        capture_output=True
    )
    if result.returncode != 0:
        print(json_error(
            f"Not logged in to registry {config.docker_registry}",
            f"Run 'docker login {config.docker_registry}' first",
            command="push"
        ))
        return 1
    
    push_results = []
    has_errors = False
    start = time.time()
    
    # Push versioned tag
    log_progress(f"Pushing {image_name}...")
    result = subprocess.run(["docker", "push", image_name], capture_output=True)
    if result.returncode == 0:
        digest = get_image_digest(image_name)
        push_results.append({"image": image_name, "success": True, "digest": digest})
    else:
        push_results.append({"image": image_name, "success": False})
        has_errors = True
    
    # Push latest tag
    latest_image = f"{config.docker_registry}/{config.docker_image_prefix}/core-server:latest"
    log_progress(f"Pushing {latest_image}...")
    result = subprocess.run(["docker", "push", latest_image], capture_output=True)
    if result.returncode == 0:
        push_results.append({"image": latest_image, "success": True})
    else:
        push_results.append({"image": latest_image, "success": False})
        has_errors = True
    
    duration = int(time.time() - start)
    
    data = {
        "version_tag": version_tag,
        "registry": config.docker_registry,
        "duration_seconds": duration,
        "pushed_images": push_results
    }
    
    if has_errors:
        print(json_error("Some images failed to push", "", command="push", data=data))
        return 1
    
    print(json_success("push", data, ["./calcifer-cli.py run"]))
    return 0

