# OpenClaw — Analisi di integrazione in Calcifer

## Cos'è OpenClaw

OpenClaw è un assistente AI personale open-source, self-hosted, che gira come **gateway Node.js** sempre attivo.
Si connette a chat app (Telegram, WhatsApp, Discord, Slack, Signal) e può eseguire comandi, navigare il web, gestire file, e estendersi tramite skill/plugin.

- **Repo**: https://github.com/openclaw/openclaw
- **Docker image**: `openclaw/openclaw:latest`
- **Runtime**: Node.js (`node dist/index.js gateway`)
- **Porta gateway**: `18789` (configurabile)
- **Health check**: `GET /health`

---

## Architettura nel contesto Calcifer

```
Telegram/WhatsApp/Discord
        │
        ▼
┌─────────────────┐     ┌──────────┐
│  OpenClaw GW    │────▶│ Google   │
│  (Node.js)      │     │ Gemini   │
│  :18789         │     │ API      │
└────────┬────────┘     └──────────┘
         │
    OTLP/HTTP :4318
         │
         ▼
┌─────────────────┐
│     Alloy       │──▶ Tempo (traces) + Prometheus (metrics) + Loki (logs)
└─────────────────┘
```

OpenClaw è un servizio **outbound** — si connette ai provider di chat, non riceve traffico HTTP da Traefik.
La Control UI (web dashboard) può opzionalmente essere esposta via Traefik con forward-auth per gestione/monitoring.

---

## Configurazione

### 1. Variabili d'ambiente

| Variabile | Descrizione | Valore suggerito |
|---|---|---|
| `OPENCLAW_GATEWAY_TOKEN` | Token di autenticazione per il gateway | Auto-generato (32 byte hex) |
| `OPENCLAW_GATEWAY_BIND` | Interfaccia di bind | `lan` |
| `OPENCLAW_GATEWAY_PORT` | Porta del gateway | `18789` |
| `GOG_KEYRING_PASSWORD` | Password per il keyring delle credenziali | Auto-generato (32 byte hex) |
| `GEMINI_API_KEY` | API key Google Gemini | Esterna (da Google AI Studio) |
| `XDG_CONFIG_HOME` | Config home nel container | `/home/node/.openclaw` |
| `NODE_ENV` | Ambiente Node.js | `production` |
| `HOME` | Home directory nel container | `/home/node` |

### 2. Onboarding non-interattivo (per bootstrap)

```bash
docker compose run --rm openclaw-gateway \
  node dist/index.js onboard \
  --non-interactive \
  --mode local \
  --auth-choice google-api-key \
  --gemini-api-key "$GEMINI_API_KEY"
```

Questo configura il modello Google Gemini senza intervento manuale.
Deve essere eseguito **una sola volta** dopo il primo avvio (i dati persistono nel volume).

### 3. Configurazione OTLP (file)

OpenClaw supporta **nativamente** l'export OTLP via il plugin `diagnostics-otel`.
Protocollo: **OTLP/HTTP** (protobuf) sulla porta `4318`.

Il file di configurazione va montato in `/home/node/.openclaw/config.json`:

```json
{
  "plugins": {
    "allow": ["diagnostics-otel"],
    "entries": {
      "diagnostics-otel": {
        "enabled": true
      }
    }
  },
  "diagnostics": {
    "enabled": true,
    "otel": {
      "enabled": true,
      "endpoint": "http://alloy:4318",
      "protocol": "http/protobuf",
      "serviceName": "openclaw-gateway",
      "traces": true,
      "metrics": true,
      "logs": true,
      "sampleRate": 1.0,
      "flushIntervalMs": 15000
    }
  }
}
```

**Segnali esportati:**
- **Metrics**: contatori e istogrammi per token usage, message flow, queueing
- **Traces**: span per model usage e processing
- **Logs**: esportati via OTLP quando `diagnostics.otel.logs` è abilitato

### 4. Persistenza (volumi)

| Path nel container | Contenuto | Mount suggerito |
|---|---|---|
| `/home/node/.openclaw` | Config, stato, keyring, memory, skills | `${DATA_DIR}/openclaw/config` |
| `/home/node/.openclaw/workspace` | Workspace di lavoro (file generati, progetti) | `${DATA_DIR}/openclaw/workspace` |

Entrambi i path **devono** essere montati come volumi per sopravvivere a restart/rebuild.

---

## Servizio docker-compose suggerito

```yaml
  # ==================== OPENCLAW ====================
  openclaw:
    image: openclaw/openclaw:latest
    container_name: calcifer_cloud_openclaw
    restart: unless-stopped
    depends_on:
      alloy:
        condition: service_healthy
    environment:
      - HOME=/home/node
      - NODE_ENV=production
      - TERM=xterm-256color
      - OPENCLAW_GATEWAY_BIND=${OPENCLAW_GATEWAY_BIND:-lan}
      - OPENCLAW_GATEWAY_PORT=18789
      - OPENCLAW_GATEWAY_TOKEN=${OPENCLAW_GATEWAY_TOKEN}
      - GOG_KEYRING_PASSWORD=${GOG_KEYRING_PASSWORD}
      - XDG_CONFIG_HOME=/home/node/.openclaw
      - GEMINI_API_KEY=${GEMINI_API_KEY}
    volumes:
      - ${DATA_DIR:-/var/lib/calcifer/cloud}/openclaw/config:/home/node/.openclaw
      - ${DATA_DIR:-/var/lib/calcifer/cloud}/openclaw/workspace:/home/node/.openclaw/workspace
      - ./openclaw/config.json:/home/node/.openclaw/config.json:ro
    networks:
      - calcifer_net
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:18789/health || exit 1"]
      interval: 30s
      timeout: 10s

> **Nota**: `--allow-unconfigured` è per il bootstrap iniziale. Dopo l'onboarding, configurare
> `gateway.auth.token` e rimuovere il flag.

---

## Integrazione con il bootstrap Calcifer

### Nuove variabili d'ambiente (da aggiungere a `calcifer-cli/commands/env.py`)

```python
"openclaw": {
    "OPENCLAW_GATEWAY_TOKEN": ("auto", "OpenClaw gateway auth token"),
    "GOG_KEYRING_PASSWORD": ("auto", "OpenClaw keyring encryption password"),
    "GEMINI_API_KEY": ("external", "Google Gemini API key"),
},
```

### Sequenza di bootstrap

1. **Creazione directory**: `mkdir -p ${DATA_DIR}/openclaw/{config,workspace}`
2. **Deploy config.json**: copia il file OTLP config nel volume
3. **Avvio container**: `docker compose up -d openclaw`
4. **Onboarding** (una tantum, solo se non già configurato):
   ```bash
   docker compose run --rm openclaw \
     node dist/index.js onboard \
     --non-interactive \
     --mode local \
     --auth-choice google-api-key \
     --gemini-api-key "$GEMINI_API_KEY"
   ```
5. **Configurazione canali** (manuale, post-bootstrap):
   - Telegram: `docker compose run --rm openclaw node dist/index.js setup telegram`
   - WhatsApp: richiede scansione QR code

### Esposizione Control UI (opzionale)

Se si vuole esporre la dashboard web via Traefik:

```yaml
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.openclaw.rule=Host(`claw.${DOMAIN:-dmgiangi.dev}`)"
      - "traefik.http.routers.openclaw.entrypoints=websecure"
      - "traefik.http.routers.openclaw.tls.certresolver=letsencrypt"
      - "traefik.http.routers.openclaw.middlewares=forward-auth@file"
      - "traefik.http.services.openclaw.loadbalancer.server.port=18789"
```

Aggiungere anche la regola in `auth-config.yaml`:

```yaml
  claw.dmgiangi.dev:
    allowed_groups: [admins]
```

---

## Supporto OTLP — Riepilogo

| Segnale | Supportato | Note |
|---|---|---|
| **Traces** | ✅ | Span per model usage, processing pipeline |
| **Metrics** | ✅ | Token usage, message flow, queueing counters/histograms |
| **Logs** | ✅ | Export via OTLP (attenzione al volume) |
| **Protocollo** | OTLP/HTTP | Porta `4318` (non gRPC `4317`) |
| **Sample rate** | Configurabile | `sampleRate: 0.0-1.0` (solo root spans) |
| **Flush interval** | Configurabile | `flushIntervalMs` (min 1000ms) |

Alloy è già configurato per ricevere OTLP/HTTP sulla porta `4318` — nessuna modifica necessaria al collector.

---

## Modello Google Gemini

| Aspetto | Dettaglio |
|---|---|
| **Provider** | Google Gemini (API key) |
| **Env var** | `GEMINI_API_KEY` |
| **Modello default** | `gemini-2.5-flash` |
| **Ottenere la key** | [Google AI Studio](https://aistudio.google.com/apikey) |
| **Rotazione chiavi** | Supportata (multiple keys con fallback) |
| **Web search grounding** | Supportato nativamente con Gemini |

---

## Risorse stimate

| Risorsa | Minimo | Consigliato |
|---|---|---|
| **RAM** | 512MB | 1-2GB |
| **CPU** | 0.5 vCPU | 1 vCPU |
| **Disco** | 1GB | 5GB (workspace + skills) |

---

## Checklist pre-deploy

- [ ] Ottenere `GEMINI_API_KEY` da Google AI Studio
- [ ] Aggiungere variabili a `calcifer-cli/commands/env.py`
- [ ] Creare `infrastructure/cloud/openclaw/config.json` con OTLP config
- [ ] Aggiungere servizio a `docker-compose.yaml`
- [ ] Aggiungere regola authz in `auth-config.yaml` (se Control UI esposta)
- [ ] Aggiungere step onboarding al bootstrap
- [ ] Configurare canale di comunicazione (Telegram/WhatsApp/Discord)
