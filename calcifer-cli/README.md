# Calcifer CLI

LLM-friendly deployment and management tool for the Calcifer IoT platform.

## Features

- **JSON Output**: All commands return structured JSON for easy parsing by LLMs
- **Multi-target**: Deploy to `home` (LAN) or `cloud` (public) environments
- **Git-based deployment**: Uses git pull for deployments (no image registry needed)
- **Health checks**: Built-in smoke tests for all services
- **Ansible integration**: Run playbooks directly from CLI

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
| `ansible` | Run Ansible playbooks |

## Usage

### Check status
```bash
./deploy status --target cloud
```

### Deploy
```bash
./deploy run --target cloud --branch master
```

### View logs
```bash
./deploy logs grafana --target cloud -n 100
```

### Run health checks
```bash
./deploy test --target cloud
```

### Rollback
```bash
./deploy rollback --target cloud --confirm
```

## Output Format

All commands return JSON with this structure:

```json
{
  "timestamp": "2026-03-22T15:30:00Z",
  "command": "status",
  "success": true,
  "environment": "production",
  "server": "dmgiangi.dev",
  "data": { ... },
  "errors": [],
  "next_actions": ["./deploy test --target cloud"]
}
```

## Configuration

Configuration is loaded from:
1. `deploy.conf` - Default settings
2. Environment variables - Override defaults

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DEPLOY_TARGET` | Target environment | `home` |
| `CLOUD_HOST` | Cloud server hostname | `dmgiangi.dev` |
| `HOME_HOST` | Home server IP | `192.168.8.180` |

## Testing

```bash
cd calcifer-cli
pip install pytest pytest-mock
pytest tests/ -v
```

## Project Structure

```
calcifer-cli/
├── calcifer-cli.py    # Main entry point
├── commands/          # Command implementations
│   ├── status.py
│   ├── run.py
│   ├── logs.py
│   ├── test.py
│   ├── rollback.py
│   ├── build.py
│   ├── push.py
│   ├── sync_env.py
│   └── ansible.py
├── utils/             # Shared utilities
│   ├── config.py      # Configuration management
│   ├── output.py      # JSON formatting
│   ├── ssh.py         # SSH helpers
│   ├── git.py         # Git helpers
│   └── docker.py      # Docker helpers
└── tests/             # Unit tests
```

