# Cloud observability

The cloud observability stack uses Grafana Alloy, VictoriaMetrics, VictoriaLogs,
VictoriaTraces, Grafana Operator, and Velero in the `monitoring` namespace.

Deployment settings:

- Grafana hostname: `grafana.calcifer.tech`
- Metrics retention: 365 days on local ext4 persistent volume, enforced by VictoriaMetrics.
- Logs retention: 14 days on local ext4 persistent volume, enforced by VictoriaLogs (excluded from remote Velero backup).
- Trace retention: 7 days with an 8 GiB data cap on a 10 GiB local persistent
  volume, enforced by VictoriaTraces.
- Disaster recovery: Velero with Kopia File System Backup schedules daily backups
  of observability PVCs to the private `backups` container in `calciferobs`.
- Azure credentials for Velero use a dedicated service principal with
  `Storage Blob Data Contributor` scoped to the `backups` container; all
  credentials remain SOPS-encrypted.

The Grafana Operator, Alloy, VictoriaMetrics, VictoriaLogs, VictoriaTraces, and
Velero charts are pinned to the versions in `helmrepositories.yaml`/`helmreleases.yaml`.
Official community dashboards from Grafana.com are declaratively imported via
Grafana Operator (`GrafanaDashboard` with `grafanaCom`).

Authorization-server traces use OTLP/HTTP to the Alloy instance in each
cluster. Home Alloy forwards them through the private WireGuard path with TLS,
source-IP allowlisting, and dedicated SOPS-encrypted basic-auth credentials.
Traefik rewrites ingress paths to route Home metrics, logs, and traces to their
respective Victoria services.

The service-account token is generated at runtime by Grafana Operator in the
`monitoring` namespace. Rotate it by changing the token expiry in
`grafana/grafana-service-account.yaml` before `2027-09-13`.
