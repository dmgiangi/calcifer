## Why

The current observability deployment on `calcifer-cloud` is fragmented across Thanos (microservices: Receive, Query, StoreGateway, Compactor), monolithic Loki, and VictoriaTraces. This setup consumes significant CPU/RAM on the resource-constrained single-node K3s VPS (4 vCPU, 4 GiB RAM) and relies on Azure Blob as an active primary database with fragile authentication. Migrating to a unified Victoria stack (VictoriaMetrics, VictoriaLogs, VictoriaTraces) backed by local ext4 PVCs eliminates complex live object storage dependencies, lowers memory usage, standardizes ingestion, and establishes a uniform disaster recovery path to Azure Blob using Velero and Kopia File System Backup. Historical telemetry is intentionally discarded during this cutover.

## What Changes

- Replace Thanos with single-node VictoriaMetrics (`victoria-metrics-single` Helm release) as the primary metrics ingest (`/api/v1/write`) and PromQL-compatible query engine, configured with 365-day retention.
- Replace Loki with single-node VictoriaLogs (`victoria-logs-single` Helm release) for log collection via Loki push protocol (`/insert/loki/api/v1/push`) and LogsQL querying, configured with 14-day retention.
- Retain VictoriaTraces (`victoria-traces-single` Helm release) on local ext4 storage, preserving OpenTelemetry trace ingestion and Jaeger-compatible query endpoints, configured with 7-day retention.
- Configure all three backends to use `local-path` ext4 PVCs for active runtime data, removing active Azure Blob storage requirements from database runtimes.
- Reconfigure Grafana Alloy on `calcifer-cloud` and `calcifer-home` to ship metrics to VictoriaMetrics, logs to VictoriaLogs, and traces to VictoriaTraces.
- Modernize private WireGuard ingest routes, TLS certificates, and auth middleware on `calcifer-cloud` to generic signal-based hostnames (`metrics-ingest.calcifer.tech`, `logs-ingest.calcifer.tech`, `traces-ingest.calcifer.tech`), replacing legacy Thanos, Loki, and Tempo names.
- Update Grafana datasources (`GrafanaDatasource`) to connect to VictoriaMetrics (Prometheus type), VictoriaLogs, and update trace-to-logs correlation in VictoriaTraces.
- Adopt standard Grafana.com community dashboards (`GrafanaDashboard` with `grafanaCom`) for Kubernetes views, Node Exporter, VictoriaTraces, and VictoriaMetrics/VictoriaLogs operational visibility.
- Install and configure Velero with the Microsoft Azure plugin and Kopia node-agent to perform scheduled, deduplicated, and encrypted File System Backups of metrics and traces PVCs to Azure Blob Storage, while excluding VictoriaLogs data.
- Decommission and remove Thanos and Loki Helm releases, associated PVCs, network policies, and obsolete live object store secrets.

## Capabilities

### New Capabilities
- `observability-backup-recovery`: Schedule, execute, and restore file-system backups of the local VictoriaMetrics and VictoriaTraces PVCs to Azure Blob Storage using Velero and Kopia; VictoriaLogs remains local-only.

### Modified Capabilities
- `cloud-metrics-observability`: Ingest Kubernetes metrics into VictoriaMetrics via Prometheus remote write, store them on local ext4 PVC, and expose PromQL queries to Grafana.
- `cloud-log-observability`: Ingest Kubernetes workload logs into VictoriaLogs via the Loki push protocol, store them on local ext4 PVC, and expose query capabilities to Grafana.
- `observability-blob-storage`: Repurpose Azure Blob storage from live database object storage to offsite disaster recovery destination for Velero/Kopia backups.
- `observability-private-ingest`: Forward authenticated cross-cluster metrics and logs from Home Alloy over WireGuard to VictoriaMetrics and VictoriaLogs endpoints.
- `grafana-observability-management`: Provision Grafana datasources for VictoriaMetrics and VictoriaLogs, and import standard community dashboards from Grafana.com.

## Impact

- **Observability Applications (`clusters/calcifer-cloud/apps/observability/`)**:
  - Adds Helm repositories and Helm releases for `victoria-metrics-single` and `victoria-logs-single`.
  - Removes Helm releases for `thanos` and `loki`.
  - Updates `alloy` config to point remote write to VictoriaMetrics and log push to VictoriaLogs.
  - Updates `home-ingest-traefik.yaml` to route private WireGuard traffic to VictoriaMetrics and VictoriaLogs.
  - Updates `grafana/grafana-datasources.yaml` to register VictoriaMetrics and VictoriaLogs.
  - Replaces custom dashboard ConfigMaps with declarative `GrafanaDashboard` resources referencing Grafana.com IDs.
- **Home Observability (`clusters/calcifer-home/apps/observability/`)**:
  - Updates `alloy` Helm release config to target the updated ingest paths.
- **Backup Infrastructure**:
  - Introduces Velero Helm release and `BackupStorageLocation` targeting Azure Blob `calciferobs`.
- **Azure Infrastructure**:
  - Decommissions active `thanos` and `loki` container usage; creates/configures backup container for Velero.
- **Data Continuity**:
  - Existing metric blocks and log streams in Thanos and Loki are dropped upon cutover.
