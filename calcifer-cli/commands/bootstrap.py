"""
Bootstrap Command - Remote server bootstrap.
- Clone repository
- Setup volume folders
- Generate environment
- Create systemd service
- Start services
"""

import argparse
import subprocess
from typing import List

import sys
from pathlib import Path
CLI_DIR = Path(__file__).parent.parent.resolve()
sys.path.insert(0, str(CLI_DIR))

from utils.config import Config
from commands.env import cmd_env


SYSTEMD_SERVICE = '''[Unit]
Description=Calcifer Docker Compose
Requires=docker.service
After=docker.service network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory={deploy_dir}/infrastructure/{compose_dir}
ExecStart=/usr/bin/docker compose up -d
ExecStop=/usr/bin/docker compose down
TimeoutStartSec=300

[Install]
WantedBy=multi-user.target
'''


def print_header(text: str) -> None:
    print(f"\n{'─' * 60}")
    print(f"  {text}")
    print(f"{'─' * 60}")


def print_step(icon: str, text: str) -> None:
    print(f"  {icon}  {text}")


def ssh_run(config, command: str, check: bool = True) -> subprocess.CompletedProcess:
    """Run SSH command on remote server."""
    target = config.current_target
    ssh_cmd = [
        "ssh", "-i", target.ssh_key,
        "-o", "StrictHostKeyChecking=accept-new",
        f"{target.user}@{target.host}",
        command
    ]
    return subprocess.run(ssh_cmd, capture_output=True, text=True, check=check)


def cmd_bootstrap(args: List[str]) -> int:
    """Bootstrap command handler."""
    parser = argparse.ArgumentParser(prog="calcifer-cli.py bootstrap")
    parser.add_argument("--target", choices=["home", "cloud"], default="cloud")
    parser.add_argument("--repo", default="https://github.com/dmgiangi/calcifer.git",
                        help="Git repository URL")
    parser.add_argument("--branch", default="master", help="Git branch")
    parser.add_argument("--skip-env", action="store_true", 
                        help="Skip environment configuration (use existing .env)")
    opts = parser.parse_args(args)

    config = Config.load(target=opts.target)
    target = config.current_target

    print_header(f"BOOTSTRAP SERVER ({opts.target.upper()})")
    print()
    print(f"  🎯 Server: {target.user}@{target.host}")
    print(f"  📁 Deploy dir: {target.deploy_dir}")
    print(f"  📦 Repository: {opts.repo}")
    print(f"  🌿 Branch: {opts.branch}")

    # Step 1: Clone repository
    print_header("1. CLONE REPOSITORY")

    # Check if already exists
    result = ssh_run(config, f"test -d {target.deploy_dir}/.git && echo 'exists'", check=False)
    if 'exists' in result.stdout:
        print_step("📂", "Repository exists, pulling latest...")
        ssh_run(config, f"cd {target.deploy_dir} && git fetch && git checkout {opts.branch} && git pull")
    else:
        print_step("📥", f"Cloning {opts.repo}...")
        # Create parent directory if needed
        ssh_run(config, f"sudo mkdir -p {target.deploy_dir}", check=False)
        ssh_run(config, f"sudo chown {target.user}:{target.user} {target.deploy_dir}")
        ssh_run(config, f"git clone -b {opts.branch} {opts.repo} {target.deploy_dir}")
    print_step("✅", "Repository ready")

    # Step 2: Setup volume folders
    print_header("2. SETUP VOLUME FOLDERS")
    
    data_dir = f"/var/lib/calcifer/{opts.target}"
    certs_dir = "/opt/certs"
    
    folders = [
        f"{data_dir}/prometheus",
        f"{data_dir}/grafana",
        f"{data_dir}/keycloak",
        f"{data_dir}/loki",
        f"{data_dir}/tempo",
        certs_dir,
    ]
    
    for folder in folders:
        print_step("📁", f"Creating {folder}")
        ssh_run(config, f"sudo mkdir -p {folder}")
    
    # Set permissions
    print_step("🔒", "Setting permissions...")
    ssh_run(config, f"sudo chown -R 65534:65534 {data_dir}/prometheus")  # nobody
    ssh_run(config, f"sudo chown -R 472:0 {data_dir}/grafana")  # grafana
    ssh_run(config, f"sudo chown -R 1000:1000 {data_dir}/keycloak")  # keycloak
    ssh_run(config, f"sudo chown -R 10001:10001 {data_dir}/loki")
    ssh_run(config, f"sudo chown -R 10001:10001 {data_dir}/tempo")
    ssh_run(config, f"sudo chmod 700 {certs_dir}")
    print_step("✅", "Folders ready")

    # Step 3: Generate environment
    print_header("3. CONFIGURE ENVIRONMENT")
    
    if opts.skip_env:
        print_step("⏭️ ", "Skipping (--skip-env)")
    else:
        print_step("🔧", "Running env configuration...")
        result = cmd_env(["--target", opts.target])
        if result != 0:
            print_step("❌", "Environment configuration failed!")
            return 1
    
    # Step 4: Create systemd service
    print_header("4. CREATE SYSTEMD SERVICE")
    
    service_content = SYSTEMD_SERVICE.format(
        deploy_dir=target.deploy_dir,
        compose_dir=target.compose_dir
    )
    
    # Write service file
    print_step("📝", "Creating /etc/systemd/system/calcifer.service")
    ssh_run(config, f"echo '{service_content}' | sudo tee /etc/systemd/system/calcifer.service > /dev/null")
    ssh_run(config, "sudo systemctl daemon-reload")
    ssh_run(config, "sudo systemctl enable calcifer")
    print_step("✅", "Service enabled")

    # Step 5: Start services
    print_header("5. START SERVICES")
    print_step("🚀", "Running docker compose up...")

    result = ssh_run(config,
        f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && docker compose up -d 2>&1",
        check=False)

    if result.returncode != 0:
        print_step("⚠️ ", "Some services may have issues:")
        for line in result.stdout.split('\n')[-10:]:
            if line.strip():
                print(f"      {line}")

    # Wait for services
    print_step("⏳", "Waiting for services to start...")
    ssh_run(config, "sleep 10", check=False)

    # Check status
    result = ssh_run(config,
        f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && docker compose ps --format '{{{{.Name}}}} {{{{.Status}}}}'",
        check=False)

    print()
    for line in result.stdout.strip().split('\n'):
        if line:
            if 'Up' in line or 'healthy' in line:
                print(f"      ✅ {line}")
            elif 'Restarting' in line:
                print(f"      ⚠️  {line}")
            else:
                print(f"      ❌ {line}")

    # Step 6: Wait for Keycloak and run init (cloud only)
    if opts.target == "cloud":
        print_header("6. CONFIGURE KEYCLOAK")
        print_step("⏳", "Waiting for Keycloak to be healthy...")

        # Wait for Keycloak healthy
        for i in range(30):
            result = ssh_run(config,
                "docker inspect calcifer_cloud_keycloak --format '{{.State.Health.Status}}' 2>/dev/null",
                check=False)
            status = result.stdout.strip()
            if status == "healthy":
                print_step("✅", "Keycloak is healthy")
                break
            print_step("⏳", f"Waiting... ({status})")
            ssh_run(config, "sleep 5", check=False)

        # Run keycloak-init
        print_step("🔧", "Running keycloak-init (configure Google IDP)...")
        ssh_run(config,
            f"cd {target.deploy_dir}/infrastructure/{target.compose_dir} && docker compose up -d --force-recreate keycloak-init",
            check=False)
        ssh_run(config, "sleep 15", check=False)

        # Show init logs
        result = ssh_run(config, "docker logs calcifer_cloud_keycloak_init 2>&1 | tail -10", check=False)
        for line in result.stdout.strip().split('\n'):
            if '[INIT]' in line:
                print(f"      {line}")

    print_header("BOOTSTRAP COMPLETE")
    print()

    if opts.target == "cloud":
        domain = "dmgiangi.dev"
        print(f"  🌐 Services available at:")
        print(f"      https://home.{domain}")
        print(f"      https://grafana.{domain}")
        print(f"      https://prometheus.{domain}")
        print(f"      https://keycloak.{domain}")
        print(f"      https://traefik.{domain}")
        print()
        print("  🔐 ACCESS:")
        print()
        print("      Keycloak Admin Console:")
        print("        https://keycloak.dmgiangi.dev/admin/master/console/")
        print("        Username: admin")
        print("        Password: ${KEYCLOAK_ADMIN_PASSWORD} from .env")
        print()
        print("      App Services (Grafana, Prometheus, etc.):")
        print("        Login with Google (authorized emails from ADMIN_EMAILS)")
        print()
    else:
        print(f"  🌐 Services available at:")
        print(f"      http://192.168.8.180:3000  (Grafana)")
        print(f"      http://192.168.8.180:9090  (Prometheus)")
        print()

    return 0

