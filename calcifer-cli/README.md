# Calcifer CLI

Environment configuration and deployment tool for Calcifer IoT platform.

## Features

- **Environment Management**: Generate and sync environment configuration
- **Secret Generation**: Auto-generates internal secrets (passwords, tokens)
- **External Credentials**: Prompts for external secrets (Google OAuth)
- **Remote Sync**: Pushes configuration to deployment servers

## Requirements

| Requirement | Version | Notes |
|-------------|---------|-------|
| Python | 3.8+ | Standard library only |
| SSH | OpenSSH | With key-based authentication |

## Usage

```bash
./calcifer-cli.py env [--target home|cloud]
```

## Environment Setup

The CLI manages `.env.{target}` files locally (git-ignored) and syncs them to servers.

### Cloud Environment

```bash
./calcifer-cli.py env --target cloud
```

This will:
1. Load existing `.env.cloud` if present
2. Auto-generate internal secrets (Keycloak, Grafana, Forward Auth)
3. Prompt for external credentials (Google OAuth)
4. Save locally to `.env.cloud`
5. Push to cloud server

### Home Environment

```bash
./calcifer-cli.py env --target home
```

Simpler setup for home server (no OAuth, no external services).

## Generated Secrets

| Variable | Description |
|----------|-------------|
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin password |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password |
| `KEYCLOAK_CLIENT_SECRET` | OAuth client secret |
| `FORWARD_AUTH_SECRET` | Cookie encryption key (64 hex chars) |

## Required External Credentials

For cloud deployment with Google OAuth:

| Variable | Description | Where to get |
|----------|-------------|--------------|
| `GOOGLE_CLIENT_ID` | Google OAuth Client ID | [Google Cloud Console](https://console.cloud.google.com/apis/credentials) |
| `GOOGLE_CLIENT_SECRET` | Google OAuth Client Secret | Same as above |
| `ADMIN_EMAILS` | Admin email addresses | Your Google email(s) |

## Bootstrap from Scratch

```bash
# 1. Clone the repository
git clone https://github.com/dmgiangi/calcifer.git
cd calcifer/calcifer-cli

# 2. Configure cloud environment
./calcifer-cli.py env --target cloud

# 3. SSH to server and start services
ssh user@server "cd /opt/calcifer/infrastructure/cloud && docker compose up -d"
```

## Configuration

SSH targets are configured in `utils/config.py`:

| Target | Host | Deploy Directory |
|--------|------|------------------|
| `cloud` | dmgiangi.dev | /opt/calcifer |
| `home` | 192.168.8.180 | /opt/calcifer |

