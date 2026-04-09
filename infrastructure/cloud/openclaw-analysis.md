# OpenClaw — Integrazione in Calcifer

## Cos'è OpenClaw

Assistente AI personale open-source, self-hosted, gateway Node.js sempre attivo.
Si connette a chat app (Telegram, WhatsApp, Discord, Slack, Signal).

- **Repo**: https://github.com/openclaw/openclaw
- **Docker image**: `ghcr.io/openclaw/openclaw:latest`
- **Runtime**: `node dist/index.js gateway`
- **Porta**: `18789`
- **Health check**: `GET /health`

---

## Architettura

```
Browser → Traefik → forward-auth (Keycloak) → OpenClaw Control UI
                                                    │
Telegram/WhatsApp/Discord ─────────────────────► OpenClaw GW ──► Google Gemini API
                                                    │
                                               OTLP/HTTP :4318
                                                    │
                                                  Alloy ──► Tempo + Prometheus + Loki
```

---

## Configurazione dichiarativa (no onboarding)

Tutta la configurazione è nel file `openclaw/config.json`, montato come `openclaw.json:ro`.
**L'onboarding CLI (`onboard`) NON è necessario.** Tutto è dichiarativo.

### File: `openclaw/config.json`

```json
{
  "gateway": {
    "mode": "local",
    "bind": "lan",
    "port": 18789,
    "trustedProxies": ["172.16.0.0/12"],
    "auth": {
      "mode": "trusted-proxy",
      "trustedProxy": {
        "userHeader": "X-Forwarded-User"
      }
    },
    "controlUi": {
      "allowedOrigins": ["https://claw.dmgiangi.dev"]
    }
  },
  "agents": {
    "defaults": {
      "model": { "primary": "google/gemini-2.5-flash" }
    }
  },
  "plugins": { ... },
  "diagnostics": { ... }
}
```

### Autenticazione: `trusted-proxy`

L'autenticazione è **delegata interamente a Keycloak** tramite forward-auth:

1. Traefik riceve la richiesta per `claw.dmgiangi.dev`
2. Forward-auth verifica l'utente con Keycloak (gruppo `admins`)
3. Forward-auth setta l'header `X-Forwarded-User` con l'email dell'utente
4. OpenClaw legge l'header da un IP fidato (`trustedProxies`) e concede accesso operator completo

**Nessun token da incollare nella UI**, nessun device pairing, nessun `dangerouslyDisableDeviceAuth`.

### Variabili d'ambiente

| Variabile | Descrizione | Necessaria |
|---|---|---|
| `GEMINI_API_KEY` | API key Google Gemini | ✅ Sì (esterna) |
| `HOME` | Home directory container | ✅ `/home/node` |
| `NODE_ENV` | Ambiente | ✅ `production` |

**Variabili rimosse** (non più necessarie):
- `OPENCLAW_GATEWAY_TOKEN` — auth è `trusted-proxy`, non `token`
- `GOG_KEYRING_PASSWORD` — keyring non usato, `GEMINI_API_KEY` è env var
- `OPENCLAW_GATEWAY_BIND/PORT` — definiti nel config file
- `XDG_CONFIG_HOME` — path default è corretto

### Persistenza

| Path nel container | Contenuto | Mount |
|---|---|---|
| `/home/node/.openclaw` | Config, stato, sessioni, memory | `${DATA_DIR}/openclaw/config` |
| `/home/node/.openclaw/workspace` | Workspace (file generati) | `${DATA_DIR}/openclaw/workspace` |
| `/home/node/.openclaw/openclaw.json` | Config dichiarativo (read-only) | `./openclaw/config.json:ro` |

**Nota**: Il file config si chiama `openclaw.json` (non `config.json`).

---

## OTLP / Observability

| Segnale | Supportato | Note |
|---|---|---|
| Traces | ✅ | Model usage, processing pipeline |
| Metrics | ✅ | Token usage, message flow |
| Logs | ✅ | Export via OTLP |
| Protocollo | OTLP/HTTP | Porta `4318` (non gRPC `4317`) |

---

## Modello

| Aspetto | Dettaglio |
|---|---|
| Provider | Google Gemini (API key) |
| Env var | `GEMINI_API_KEY` |
| Modello default | `google/gemini-2.5-flash` |
| Configurato in | `agents.defaults.model.primary` nel config |

---

## Bootstrap

Sequenza automatizzata dal CLI:

1. Creazione directory: `${DATA_DIR}/openclaw/{config,workspace}`
2. Permessi: `chown 1000:1000` (utente `node`)
3. `docker compose up -d openclaw`
4. Il config file dichiara tutto — nessun step di onboarding

### Configurazione canali (manuale, post-bootstrap)

- Telegram: `docker compose exec openclaw node dist/index.js setup telegram`
- WhatsApp: richiede scansione QR code

---

## Checklist

- [x] Config dichiarativo (`openclaw.json`)
- [x] Auth delegata a Keycloak (`trusted-proxy`)
- [x] `trustedProxies` per rete Docker
- [x] Modello Gemini via env var
- [x] OTLP verso Alloy
- [x] Control UI esposta via Traefik + forward-auth
- [ ] Configurazione canali chat (Telegram/WhatsApp)
