# Calcifer CLI

Server management and deployment tool for Calcifer IoT platform.

## Features

- **Bootstrap**: Full remote server setup from scratch
- **Environment Management**: Generate and sync secrets/configuration
- **Clean**: Wipe server while preserving SSL certificates

## Requirements

| Requirement | Version | Notes |
|-------------|---------|-------|
| Python | 3.8+ | Standard library only |
| SSH | OpenSSH | With key-based authentication |

## Commands

### `bootstrap` - Full Server Setup

```bash
./calcifer-cli.py bootstrap --target cloud
```

This will:
1. Clone repository to remote server
2. Create data volume directories with correct permissions
3. Configure environment (prompts for Google OAuth credentials)
4. Create systemd service for auto-start on boot
5. Run `docker compose up`

### `env` - Configure Environment

```bash
./calcifer-cli.py env --target cloud
```

Manages environment configuration:
- Auto-generates internal secrets (passwords, tokens)
- Prompts for external credentials (Google OAuth)
- Saves locally to `.env.{target}` (git-ignored)
- Pushes to remote server

### `clean` - Wipe Server

```bash
./calcifer-cli.py clean --target cloud --confirm
```

Removes everything EXCEPT SSL certificates:
- Repository clone
- Environment secrets
- Data volumes
- Systemd service

## Bootstrap from Scratch

```bash
# 1. Clone this repository locally
git clone https://github.com/dmgiangi/calcifer.git
cd calcifer/calcifer-cli

# 2. Bootstrap the cloud server
./calcifer-cli.py bootstrap --target cloud

# 3. Login to Keycloak admin with Google
# https://keycloak.dmgiangi.dev/admin/master/console/

# 4. Re-run keycloak-init to assign admin role
ssh user@server "cd /opt/calcifer/infrastructure/cloud && \
  docker compose up -d --force-recreate keycloak-init"
```

## Generated Secrets

| Variable | Description |
|----------|-------------|
| `GRAFANA_ADMIN_PASSWORD` | Grafana admin password |
| `KEYCLOAK_ADMIN_PASSWORD` | Keycloak admin password |
| `KEYCLOAK_CLIENT_SECRET` | OAuth client secret |
| `API_CLIENT_SECRET` | API access secret (M2M) |
| `FORWARD_AUTH_SECRET` | Cookie encryption key |

## Required External Credentials

| Variable | Description | Where to get |
|----------|-------------|--------------|
| `GOOGLE_CLIENT_ID` | Google OAuth Client ID | [Google Cloud Console](https://console.cloud.google.com/apis/credentials) |
| `GOOGLE_CLIENT_SECRET` | Google OAuth Client Secret | Same as above |
| `ADMIN_EMAILS` | Admin email addresses | Your Google email(s) |

## Directory Structure

```
/opt/calcifer/                    # Repository
/var/lib/calcifer/{target}/       # Data volumes
  ├── prometheus/
  ├── grafana/
  ├── keycloak/
  ├── loki/
  └── tempo/
/opt/certs/                       # SSL certificates (preserved on clean)
```

## Configuration

| Target | Host | Deploy Directory |
|--------|------|------------------|
| `cloud` | dmgiangi.dev | /opt/calcifer |
| `home` | 192.168.8.180 | /opt/calcifer |

