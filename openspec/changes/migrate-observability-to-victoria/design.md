## Context

`calcifer-cloud` is a single-node K3s cluster on a remote VPS (4 vCPU, 4 GiB RAM) with a local ext4 SSD and an off-cluster Azure Blob storage account (`calciferobs`). The current observability setup runs Thanos (4 components: receive, query, storegateway, compactor), monolithic Loki, and VictoriaTraces. This consumes excessive RAM/CPU, introduces multi-protocol failure points, and relies on Azure Blob as an active primary database with known client compatibility and SAS authentication limitations.

`calcifer-home` ships metrics and logs to `calcifer-cloud` via Grafana Alloy over a private WireGuard tunnel terminating at a Traefik NodePort with BasicAuth.

## Goals / Non-Goals

**Goals:**
- Replace Thanos and Loki with single-node VictoriaMetrics and VictoriaLogs, creating a unified Victoria observability suite with VictoriaTraces.
- Run all three databases exclusively on local `local-path` ext4 PVCs, decoupling database operations from cloud object storage latency.
- Unify cross-cluster ingestion in Grafana Alloy (`calcifer-cloud` and `calcifer-home`) targeting Victoria endpoints.
- Unify disaster recovery using Velero File System Backup (FSB) with Kopia into Azure Blob (`calciferobs`).
- Replace custom Grafana dashboards with standard Grafana.com community dashboards via Grafana Operator.
- Safely decommission Thanos and Loki resources without disrupting cluster operations.

**Non-Goals:**
- Retaining or migrating historical telemetry data from Thanos and Loki (user accepted data loss).
- Deploying complex CSI drivers (e.g. OpenEBS LVM LocalPV) or block-level VolumeSnapshots.
- High availability or multi-node clustering for VictoriaMetrics/VictoriaLogs.
- Querying archived backups directly from Azure Blob.

## Decisions

### 1. VictoriaMetrics and VictoriaLogs as Single-Node Backends
- **Choice**: Deploy `victoria-metrics-single` (chart `0.46.0`) and `victoria-logs-single` (chart `0.13.9`) Helm releases from `https://victoriametrics.github.io/helm-charts`.
- **Rationale**: Extreme resource efficiency, low memory footprint, simple single-binary operational model, native Prometheus remote-write compatibility, and Loki push protocol support (`/insert/loki/api/v1/push`).
- **Alternatives considered**:
  - *Keep Thanos*: Requires 4 separate pods, high memory consumption, active Azure Blob dependency.
  - *Keep Loki on local PVC*: Higher memory consumption and complex index management compared to VictoriaLogs.

### 2. Local ext4 Storage with `local-path`
- **Choice**: Mount persistent volumes using K3s's built-in `local-path` storage class (ext4 filesystem).
- **Rationale**: Victoria components are optimized for local filesystems, immutable parts, and fast mmap operations. Avoids POSIX limitations and latency of Azure Files / BlobFuse.
- **Alternatives considered**:
  - *Azure Files NFS*: High latency on query/merge, network dependency, uncertified for Victoria databases.
  - *LVM + CSI Snapshots*: Added operational overhead, requires dedicated disk partitioning and node-level volume management.

### 3. Unified Disaster Recovery via Velero and Kopia
- **Choice**: Deploy Velero with `velero-plugin-for-microsoft-azure` and Kopia-backed node-agent for File System Backup (FSB) to Azure Blob container `calciferobs/backups`.
- **Rationale**: Provides an identical, declarative backup and restore mechanism across all three observability PVCs with client-side encryption and deduplication.
- **Alternatives considered**:
  - *Separate tools (`vmbackup` + `rclone`)*: Fractured operations; `vmbackup` only works for VictoriaMetrics, leaving Logs and Traces to manual scripts.
  - *AzCopy sync*: Lacks point-in-time consistency, deduplication, and Kubernetes-aware metadata restoration.

### 4. Standard Grafana Community Dashboards
- **Choice**: Use Grafana Operator `GrafanaDashboard` CRDs with `spec.grafanaCom.id` to provision official/community dashboards (DotDC K8s cluster views, Node Exporter Full 1860, VictoriaTraces 24136, VictoriaMetrics/VictoriaLogs overviews).
- **Rationale**: Eliminates custom JSON ConfigMap maintenance and aligns with upstream dashboards maintained by component authors.

### 5. Differentiated Retention Policies
- **Choice**: Configure 365-day retention for metrics (`victoria-metrics`: `365d`), 14-day retention for logs (`victoria-logs`: `14d`), and 7-day retention for traces (`victoria-traces`: `7d`).
- **Rationale**: Metrics require long-term capacity planning and trend analysis with low per-sample storage overhead (~1 byte/sample). Logs have higher volume and decay rapidly in operational value beyond incident investigation windows.

### 6. Generic Signal-Based Ingestion Endpoints
- **Choice**: Name private WireGuard ingress hostnames, certs, and secrets by telemetry signal: `metrics-ingest.calcifer.tech`, `logs-ingest.calcifer.tech`, and `traces-ingest.calcifer.tech`.
- **Rationale**: Vendor-neutral and decoupled from underlying database implementations. Eliminates legacy Thanos/Loki/Tempo naming while ensuring complete architectural consistency.

## Risks / Trade-offs

- **[Telemetry loss during migration]** → Accepted by user; telemetry is ephemeral and non-critical.
- **[Crash-consistent File System Backup]** → In the absence of LVM block snapshots or active hooks, Velero FSB reads the live ext4 volume. Victoria data parts are immutable once flushed, making crash-consistent restore reliable in practice.
- **[VictoriaLogs query language difference]** → VictoriaLogs uses LogsQL rather than LogQL. Handled by configuring VictoriaLogs datasource and compatible log visualization panels.
