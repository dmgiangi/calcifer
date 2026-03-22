# Calcifer CLI

LLM-friendly deployment and management tool for the Calcifer IoT platform.

## Features

- **JSON Output**: All commands return structured JSON for easy parsing by LLMs
- **Multi-target**: Deploy to `home` (LAN) or `cloud` (public) environments
- **Git-based deployment**: Uses git pull for deployments (no image registry needed)
- **Health checks**: Built-in smoke tests for all services

## Requirements

### Local Machine (where CLI runs)

| Requirement | Version | Notes |
|-------------|---------|-------|
| Python | 3.8+ | Standard library only, no pip dependencies |
| Git | 2.x | For version management |
| SSH | OpenSSH | With key-based authentication configured |
| Docker | 20.x+ | Only for `build` and `push` commands |

### Target Servers

Both `home` and `cloud` servers must have:

| Requirement | Version | Installation |
|-------------|---------|--------------|
| **Ubuntu** | 22.04+ | Recommended OS |
| **Docker Engine** | 24.x+ | [Install Docker](https://docs.docker.com/engine/install/ubuntu/) |
| **Docker Compose** | v2 (plugin) | Included with Docker Engine |
| **Git** | 2.x | `sudo apt install git` |
| **SSH Server** | OpenSSH | `sudo apt install openssh-server` |

#### Docker Installation (one-time setup)

```bash
# On target server (Ubuntu 22.04+)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# Logout and login again
```

#### SSH Key Setup

```bash
# On local machine
ssh-copy-id -i ~/.ssh/github_id user@server
```

### Network Requirements

| Target | Hostname | Ports Required |
|--------|----------|----------------|
| `cloud` | dmgiangi.dev | 22 (SSH), 80/443 (HTTP/S) |
| `home` | 192.168.8.180 | 22 (SSH), 3000, 8080, 9090 |

## Installation

No installation required. Just ensure Python 3.8+ is available:

```bash
chmod +x calcifer-cli.py
./calcifer-cli.py help
```

Or use via the wrapper script:

```bash
./deploy help
```

## Commands

| Command | Description |
|---------|-------------|
| `status` | Get current state of all services |
| `run` | Deploy to remote server via Git pull |
| `test` | Run health checks and smoke tests |
| `logs` | Fetch logs from services |
| `rollback` | Rollback to previous version |
| `build` | Build Docker images locally |
| `push` | Push images to container registry |
| `sync-env` | Copy .env files to remote server |

## Usage Examples

```bash
# Check status
./deploy status --target cloud

# Deploy latest
./deploy run --target cloud --branch master

# View logs
./deploy logs grafana --target cloud -n 100

# Run health checks
./deploy test --target cloud

# Rollback
./deploy rollback --target cloud --confirm
```

## Output Format

All commands return JSON:

```json
{
  "timestamp": "2026-03-22T15:30:00Z",
  "command": "status",
  "success": true,
  "server": "dmgiangi.dev",
  "data": { ... },
  "errors": [],
  "next_actions": ["./deploy test --target cloud"]
}
```

## Configuration

Edit `deploy.conf` in project root:

```bash
HOME_HOST=192.168.8.180
HOME_USER=dmgiangi
HOME_SSH_KEY=~/.ssh/github_id

CLOUD_HOST=dmgiangi.dev
CLOUD_USER=dmgiangi
CLOUD_SSH_KEY=~/.ssh/github_id
```

## Data Storage

Persistent data stored in `/var/lib/calcifer/<target>/`:

```
├── prometheus/    # Metrics
├── grafana/       # Dashboards
├── keycloak/      # Auth DB
├── traefik/certs/ # SSL certs
├── loki/          # Logs
└── tempo/         # Traces
```

