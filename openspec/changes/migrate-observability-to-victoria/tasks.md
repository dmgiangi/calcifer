## 1. Backend Workloads & Repositories

- [x] 1.1 Ensure VictoriaMetrics Helm repository is declared in `clusters/calcifer-cloud/apps/observability/helmrepositories.yaml`
- [x] 1.2 Define `victoria-metrics-single` HelmRelease with `local-path` ext4 storage in `clusters/calcifer-cloud/apps/observability/helmreleases.yaml`
- [x] 1.3 Define `victoria-logs-single` HelmRelease with `local-path` ext4 storage in `clusters/calcifer-cloud/apps/observability/helmreleases.yaml`
- [x] 1.4 Validate and align `victoria-traces` HelmRelease persistence and retention
- [x] 1.5 Configure NetworkPolicies and Service annotations for VictoriaMetrics and VictoriaLogs in `calcifer-cloud`

## 2. Ingestion & Cross-Cluster Ingress

- [x] 2.1 Reconfigure `calcifer-cloud` Alloy pipeline to forward metrics to VictoriaMetrics and logs to VictoriaLogs
- [x] 2.2 Update Traefik IngressRoutes and middlewares in `home-ingest-traefik.yaml` to route Home traffic to VictoriaMetrics and VictoriaLogs
- [x] 2.3 Reconfigure `calcifer-home` Alloy to push remote-write metrics and logs to the updated Cloud endpoints
- [x] 2.4 Verify scrape targets, label enrichment, and ingestion pipelines on both clusters

## 3. Grafana Datasources & Community Dashboards

- [x] 3.1 Update `GrafanaDatasource` manifests to register VictoriaMetrics and VictoriaLogs
- [x] 3.2 Update `victoria-traces` GrafanaDatasource to link trace-to-logs navigation to VictoriaLogs
- [x] 3.3 Replace custom dashboard ConfigMaps with declarative `GrafanaDashboard` resources importing Grafana.com IDs (DotDC K8s views, Node Exporter 1860, VictoriaTraces 24136, Victoria stack)
- [ ] 3.4 Verify dashboards render without broken queries or missing datasource variables in Grafana

## 4. Velero & Kopia Disaster Recovery

- [x] 4.1 Prepare SOPS-encrypted Azure storage credentials for Velero in `clusters/calcifer-cloud/`
- [x] 4.2 Define Velero HelmRelease with Azure plugin, Kopia node-agent, and `BackupStorageLocation` targeting `calciferobs`
- [x] 4.3 Configure a declarative Velero `Schedule` for periodic File System Backup of observability PVCs
- [ ] 4.4 Verify Velero node-agent connectivity and backup creation against the Azure Blob container

## 5. Decommissioning Thanos & Loki

- [x] 5.1 Remove `thanos` HelmRelease and associated Service/Pod manifests from `clusters/calcifer-cloud/apps/observability/`
- [x] 5.2 Remove `loki` HelmRelease and associated Service/Pod manifests from `clusters/calcifer-cloud/apps/observability/`
- [x] 5.3 Remove obsolete SOPS object store secrets (`thanos-objstore`, `loki-objstore`) and legacy NetworkPolicies
- [ ] 5.4 Clean up orphaned legacy PVCs on `calcifer-cloud`

## 6. End-to-End Acceptance Validation

- [ ] 6.1 Validate Flux reconciliation and health of all observability pods across `calcifer-cloud` and `calcifer-home`
- [ ] 6.2 Execute acceptance queries via Grafana HTTP API validating PromQL (VictoriaMetrics), logs (VictoriaLogs), and traces (VictoriaTraces)
- [ ] 6.3 Execute a test Velero backup and verify backup completion status and data upload in Azure Blob
