# Cloud observability

The cloud observability stack uses Grafana Alloy, VictoriaMetrics, VictoriaLogs,
VictoriaTraces, Grafana Operator, and Velero in the `monitoring` namespace.

Deployment settings:

- Grafana hostname: `grafana.calcifer.tech`
- Metrics retention: 365 days on the existing 8 GiB `local-path` volume, enforced by VictoriaMetrics.
- Logs retention: `6M` (fixed-duration months) with an 8 GiB VictoriaLogs disk-usage
  cap on the existing 8 GiB `local-path` PVC (excluded from remote Velero backup).
- Trace retention: `6M` (fixed-duration months) with a 20 GiB VictoriaTraces
  disk-usage cap on the existing 10 GiB `local-path` PVC.
- `local-path` PVC sizes are unchanged and do not impose per-claim filesystem
  quotas: they use shared node storage. Disk-pressure cleanup can therefore evict
  data before its age retention expires; the caps do not guarantee six months of
  stored history. These retention settings are deployed and verified.
- The authenticated public OTLP route on `otlp.calcifer.tech` is live, with
  TLS/all-signal smoke checks passed and custom ingestion limits deferred;
  see [Public OTLP ingestion](PUBLIC-OTLP.md)
  for client setup, credential handling, inherited defaults, and acceptance status.
- Disaster recovery: Velero with Kopia File System Backup schedules daily backups
  of observability PVCs to the private `backups` container in `stcalciferbackupitn`.
- Azure credentials for Velero use a dedicated service principal with
  `Storage Blob Data Contributor` scoped to the replacement Storage account; all
  credentials remain SOPS-encrypted.

The Grafana Operator, Alloy, VictoriaMetrics, VictoriaLogs, VictoriaTraces, and
Velero charts are pinned to the versions in `helmrepositories.yaml`/`helmreleases.yaml`.
Official community dashboards from Grafana.com are declaratively imported via
Grafana Operator (`GrafanaDashboard` with `grafanaCom`).

The `calcifer-home` collectors continuously run DNS, TCP, and HTTPS blackbox
probes from both the host and Kubernetes Pod network. The imported Prometheus
Blackbox dashboard provides per-target details, while `Home Network RCA`
correlates probe failures with CoreDNS SERVFAILs, node drops, and forwarding lag.

Authorization-server traces use OTLP/HTTP to the Alloy instance in each
cluster. Home Alloy forwards them through the private WireGuard path with TLS,
source-IP allowlisting, and dedicated SOPS-encrypted basic-auth credentials.
Traefik rewrites ingress paths to route Home metrics, logs, and traces to their
respective Victoria services.

The service-account token is generated at runtime by Grafana Operator in the
`monitoring` namespace. Rotate it by changing the token expiry in
`grafana/grafana-service-account.yaml` before `2027-09-13`.
