---
title: "Calcifer Infrastructure"
subtitle: "Complete Observability Stack for the Calcifer IoT Platform"
author: "Calcifer Team"
date: "\\today"
lang: "en"
titlepage: true
titlepage-color: "0B2C4B"
titlepage-text-color: "FFFFFF"
titlepage-rule-color: "E63946"
titlepage-rule-height: 2
toc: true
toc-own-page: true
listings: true
---

# Calcifer Infrastructure

Complete observability stack for the Calcifer IoT platform.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CALCIFER CORE SERVER                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  DEV: Spring Micrometer (HTTP push)                                  │   │
│  │  PROD: OTel Java Agent (gRPC, bytecode instrumentation)             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
         │ Traces              │ Metrics              │ Logs
         ▼                     ▼                      ▼
┌─────────────┐        ┌─────────────┐        ┌─────────────┐
│    TEMPO    │        │ PROMETHEUS  │        │    LOKI     │
│  (Traces)   │        │  (Metrics)  │        │   (Logs)    │
│  :4317/4318 │        │    :9090    │        │   :3100     │
└─────────────┘        └─────────────┘        └─────────────┘
         │                     │                      │
         └─────────────────────┼──────────────────────┘
                               ▼
                       ┌─────────────┐
                       │   GRAFANA   │
                       │    :3000    │
                       └─────────────┘
```

## Quick Start

### Development Environment

```bash
# Start all services
cd infrastructure
docker compose up -d

# Verify services are healthy
docker compose ps

# Access Grafana
open http://localhost:3000  # admin/admin
```

### Production Environment

```bash
# Download OTel Java Agent (one-time)
mkdir -p otel
curl -L -o otel/opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Start with production overrides
docker compose -f docker-compose.yaml -f docker-compose.prod.yaml up -d
```

## Services & Ports

| Service    | Port  | Description                 | URL                       |
|------------|-------|-----------------------------|---------------------------|
| Grafana    | 3000  | Visualization & Dashboards  | http://localhost:3000     |
| Prometheus | 9090  | Metrics storage & queries   | http://localhost:9090     |
| Tempo      | 3200  | Trace queries               | http://localhost:3200     |
| Tempo OTLP | 4317  | gRPC trace ingestion (prod) | grpc://localhost:4317     |
| Tempo OTLP | 4318  | HTTP trace ingestion (dev)  | http://localhost:4318     |
| Loki       | 3100  | Log queries                 | http://localhost:3100     |
| RabbitMQ   | 15672 | Management UI               | http://localhost:15672    |
| RabbitMQ   | 5672  | AMQP                        | amqp://localhost:5672     |
| RabbitMQ   | 1883  | MQTT                        | mqtt://localhost:1883     |
| Redis      | 6379  | Cache                       | redis://localhost:6379    |
| MongoDB    | 27017 | Database                    | mongodb://localhost:27017 |

## Dev vs Prod Configuration

| Aspect             | Development             | Production                 |
|--------------------|-------------------------|----------------------------|
| **Tracing Method** | Spring Micrometer (SDK) | OTel Java Agent (bytecode) |
| **Protocol**       | HTTP (OTLP)             | gRPC (OTLP)                |
| **Sampling**       | 100%                    | 10%                        |
| **Log Format**     | Human-readable console  | JSON structured            |
| **Log Level**      | DEBUG                   | INFO                       |
| **Retention**      | 7 days                  | 15-30 days                 |

## Spring Profiles

Run the application with the appropriate profile:

```bash
# Development (default)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Production (with OTel Agent)
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=calcifer-core-server \
     -Dotel.exporter.otlp.endpoint=http://tempo:4317 \
     -jar core-server.jar --spring.profiles.active=prod
```

## Grafana Datasources

Pre-configured datasources with full correlation:

- **Prometheus** → Metrics with exemplar links to traces
- **Loki** → Logs with derived fields linking to traces
- **Tempo** → Traces with links to logs and metrics

## Trace Correlation

Logs include `traceId` and `spanId` for full correlation:

```json
{
  "timestamp": "2024-01-29T12:00:00.000Z",
  "level": "INFO",
  "message": "Processing device command",
  "traceId": "abc123...",
  "spanId": "def456...",
  "application": "core-server"
}
```

## Useful Commands

```bash
# View logs
docker compose logs -f grafana

# Restart a service
docker compose restart tempo

# Check Tempo health
curl http://localhost:3200/ready

# Check Loki health
curl http://localhost:3100/ready

# Query Prometheus
curl 'http://localhost:9090/api/v1/query?query=up'

# Recreate RabbitMQ users
docker compose up -d rabbitmq-init
```

## Environment Variables

All secrets are stored in `.env` file (not committed to git). Copy `.env.example` to get started:

```bash
cp .env.example .env
# Edit .env with your values
```

### Available Variables

| Variable                  | Service  | Description                 |
|---------------------------|----------|-----------------------------|
| `MONGO_USERNAME`          | MongoDB  | Database username           |
| `MONGO_PASSWORD`          | MongoDB  | Database password           |
| `REDIS_USERNAME`          | Redis    | Cache username              |
| `REDIS_PASSWORD`          | Redis    | Cache password              |
| `RABBITMQ_ADMIN_USERNAME` | RabbitMQ | Admin username              |
| `RABBITMQ_ADMIN_PASSWORD` | RabbitMQ | Admin password              |
| `RABBITMQ_IOT_PASSWORD`   | RabbitMQ | IoT user password (see CSV) |
| `GRAFANA_ADMIN_USER`      | Grafana  | Admin username              |
| `GRAFANA_ADMIN_PASSWORD`  | Grafana  | Admin password              |
| `DOCKER_REGISTRY`         | Docker   | Registry URL                |
| `DOCKER_USERNAME`         | Docker   | Registry username           |
| `IMAGE_TAG`               | Docker   | Image tag for deployments   |

## RabbitMQ User Management

Users are managed via CSV file (`rabbitmq-users.csv`) for easy extensibility.

### CSV Format

```
username,password_env_var,tags,vhost,configure,write,read,topic_exchange,topic_write,topic_read
```

| Field              | Description                               | Example                 |
|--------------------|-------------------------------------------|-------------------------|
| `username`         | RabbitMQ username                         | `iot_user`              |
| `password_env_var` | Env variable name in `.env`               | `RABBITMQ_IOT_PASSWORD` |
| `tags`             | User tags (admin, monitoring, management) | `` (empty)              |
| `vhost`            | Virtual host                              | `/`                     |
| `configure`        | Configure permission regex                | `^mqtt-subscription-.*` |
| `write`            | Write permission regex                    | `.*`                    |
| `read`             | Read permission regex                     | `.*`                    |
| `topic_exchange`   | Exchange for topic permissions            | `amq.topic`             |
| `topic_write`      | Topic write permission regex              | `^\..*\..*\..*\..*$`    |
| `topic_read`       | Topic read permission regex               | `.*`                    |

### Adding a New User

1. **Add password to `.env`:**
   ```bash
   RABBITMQ_APP_PASSWORD=my_secret_password
   ```

2. **Add user to `rabbitmq-users.csv`:**
   ```csv
   app_user,RABBITMQ_APP_PASSWORD,,/,.*,.*,.*,,,
   ```

3. **Apply changes:**
   ```bash
   docker compose up -d rabbitmq-init
   ```

### Verify Users

```bash
# List all users
curl -s -u admin:password_admin http://localhost:15672/api/users | jq '.[].name'

# Check user permissions
curl -s -u admin:password_admin http://localhost:15672/api/permissions | jq '.[] | select(.user=="iot_user")'
```

