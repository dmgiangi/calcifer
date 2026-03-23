# calcifer-auth

Custom forward-auth middleware for Traefik that combines **OAuth2/OIDC authentication** (via Keycloak) and **group-based authorization** in a single service.

Replaces `thomseddon/traefik-forward-auth` + a separate group-auth middleware with zero extra REST calls per request.

## How it works

```
Browser → Traefik → calcifer-auth → Service
                         │
                    ┌─────┴─────┐
                    │  Session?  │
                    └─────┬─────┘
                     no   │   yes
                     ↓    │    ↓
               redirect   │  decrypt cookie
              to Keycloak  │  extract groups
                          │    ↓
                    ┌─────┴──────────┐
                    │ groups allowed  │
                    │ for this host?  │
                    └─────┬──────────┘
                     no   │   yes
                     ↓    │    ↓
                    403   │  200 + headers
                          │  (X-Forwarded-User,
                          │   X-Forwarded-Groups,
                          │   X-Grafana-Role, ...)
```

1. **First visit** — no session cookie → redirect to Keycloak login
2. **OAuth callback** — exchange code for tokens, extract `email` + `groups` from JWT, store in AES-GCM encrypted cookie
3. **Subsequent requests** — decrypt cookie, check user groups against per-host rules from `auth-config.yaml`
4. **Authorized** → `200` with injected headers for downstream services
5. **Denied** → `403 Forbidden`

## Configuration

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `OIDC_ISSUER_URL` | ✅ | — | Keycloak realm URL (e.g. `https://keycloak.example.com/realms/myrealm`) |
| `OIDC_CLIENT_ID` | | `calcifer-gateway` | OAuth2 client ID |
| `OIDC_CLIENT_SECRET` | ✅ | — | OAuth2 client secret |
| `SECRET` | ✅ | — | Encryption key for session cookies (any string, hashed to AES-256) |
| `AUTH_HOST` | ✅ | — | Hostname for OAuth callback (e.g. `auth.example.com`) |
| `COOKIE_DOMAIN` | ✅ | — | Cookie domain, shared across subdomains (e.g. `example.com`) |
| `COOKIE_NAME` | | `_calcifer_auth` | Session cookie name |
| `CALLBACK_PATH` | | `/_oauth` | OAuth callback path |
| `RULES_PATH` | | `/etc/calcifer-auth/auth-config.yaml` | Path to authorization rules file |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | | `alloy:4317` | OTLP gRPC endpoint for traces and metrics |

### Authorization rules (`auth-config.yaml`)

```yaml
default_policy: deny  # deny or allow when host has no rule

rules:
  grafana.example.com:
    allowed_groups: [admins, operators, viewers]
    headers:                          # optional: inject headers per group
      admins:
        X-Grafana-Role: Admin
      operators:
        X-Grafana-Role: Editor
      viewers:
        X-Grafana-Role: Viewer

  prometheus.example.com:
    allowed_groups: [admins]

  # Add new services here — one entry per host
  new-service.example.com:
    allowed_groups: [admins, operators]
```

## Telemetry

Exports traces and metrics via OTLP gRPC to Alloy (or any OTel Collector).

**Traces** — one span per forwarded request with attributes:
- `http.host`, `http.uri`, `auth.user`, `auth.groups`, `auth.result`

**Metrics:**
- `calcifer_auth.requests` — total forwarded auth requests
- `calcifer_auth.authorized` — authorized requests
- `calcifer_auth.denied` — denied requests (403)
- `calcifer_auth.login_redirects` — redirects to Keycloak login
- `calcifer_auth.callbacks` — OAuth callbacks processed
- `calcifer_auth.request_duration_ms` — request processing duration

## Build

```bash
go build -o calcifer-auth .
```

## Docker

```bash
docker build -t calcifer-auth .
```

Built as a multi-stage image (`golang:1.25-alpine` → `alpine:3.19`), producing a ~15MB image.

## Endpoints

| Path | Description |
|---|---|
| `/` | ForwardAuth handler (called by Traefik) |
| `/_oauth` | OAuth2 callback (redirect from Keycloak) |
| `/logout` | Clear session and redirect to Keycloak logout |
| `/health` | Health check (`200 OK`) |

