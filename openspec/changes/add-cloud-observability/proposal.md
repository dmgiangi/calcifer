## Why

`calcifer-cloud` has no durable, queryable observability platform. Operators need Kubernetes metrics and logs in Grafana without deploying the resource-heavy Mimir/Tempo/Redpanda stack on the single 4-vCPU, 4-GiB K3s node.

## What Changes

- Add a single-node metrics platform based on Grafana Alloy and Thanos, queried with PromQL through Grafana.
- Add a single-replica, monolithic Loki deployment for Kubernetes logs and LogQL queries.
- Persist Thanos metric blocks and Loki log data in separate private Azure Blob containers of the `calciferobs` storage account.
- Install Grafana Operator to manage the Grafana instance, its Thanos and Loki datasources, and dashboard resources declaratively.
- Provision open-source Loki and Thanos operational dashboards when compatible upstream dashboards exist, alongside a small local baseline dashboard.
- Collect and display health, resource, ingestion, and query metrics for Grafana, Grafana Operator, Alloy, Thanos, and Loki through the same metrics platform.
- Provision a Grafana service-account token for authenticated HTTP API automation and a local `admin` user with a randomly generated password stored only in SOPS.
- Require a live CLI-driven acceptance test through Flux, kubectl, and Grafana's HTTP API before the change can be considered complete.
- Store Azure Blob credentials only in SOPS-encrypted Kubernetes Secrets; use the Thanos container-scoped SAS and a Loki-only container-scoped Azure service principal required by the pinned Loki client, without exposing any observability backend publicly.
- Exclude Redpanda, Tempo, Mimir, multi-node replication, and high availability from this first deployment.

## Capabilities

### New Capabilities

- `cloud-metrics-observability`: Collect Kubernetes metrics, retain them with Thanos, and expose PromQL-compatible queries to Grafana.
- `cloud-log-observability`: Collect Kubernetes workload logs with Alloy and provide LogQL queries through a monolithic Loki backend.
- `grafana-observability-management`: Provision Grafana, datasources, and observability dashboards declaratively through Grafana Operator.
- `observability-blob-storage`: Secure and configure Azure Blob persistence for the observability backends.

### Modified Capabilities

None.

## Impact

- Adds Flux-managed Helm repositories and HelmReleases plus manifests under `clusters/calcifer-cloud/apps/observability/`.
- Adds a `monitoring` namespace, local PVCs for recent Thanos data and Loki working state, and a SOPS-encrypted Azure Blob credential Secret.
- Requires the Azure storage account `calciferobs`, resource group `rg-calcifer-westeurope-001`, `westeurope`/LRS configuration, private `thanos` and `loki` containers, and network access limited to `136.144.222.128`.
- Adds Helm chart dependencies for Grafana Operator, Grafana Alloy, Thanos, and Loki.
- Adds a Grafana service-account token Secret generated at runtime by Grafana Operator; it is intentionally not stored in Git.
- Requires Flux and kubectl CLI access to reconcile and inspect the cluster, plus authenticated Grafana API access for end-to-end validation.
