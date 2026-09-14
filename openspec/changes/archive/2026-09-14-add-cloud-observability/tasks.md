## 1. Azure and secret prerequisites

- [x] 1.1 Rotate both `calciferobs` storage account access keys and confirm no workload uses the previously exposed key.
- [x] 1.2 Create the private `thanos` and `loki` Blob containers in `calciferobs`; retain the Hot tier and the `136.144.222.128` data-plane allowlist.
- [x] 1.3 Generate separate HTTPS-only, least-privilege credentials for the `thanos` and `loki` containers: a container-scoped SAS for Thanos and a container-scoped Azure service principal for Loki because the pinned Loki Azure client rejects SAS connection strings.
- [x] 1.4 Update `thanos-objstore.sops.yaml` with the Thanos SAS connection string and create the SOPS-encrypted Loki service-principal credential Secret without committing raw credentials.
- [x] 1.5 Generate a cryptographically random Grafana `admin` password locally without printing it, then create a SOPS-encrypted administrator credential Secret with username `admin`.
- [x] 1.6 Record the chosen Grafana hostname and initial metric/log retention periods.

## 2. Flux and namespace foundation

- [x] 2.1 Add an observability Kustomize root under `clusters/calcifer-cloud/apps/` and include it from the existing cloud applications Kustomization.
- [x] 2.2 Create the `monitoring` namespace and include the encrypted object-store and Grafana credential Secrets in the observability Kustomization.
- [x] 2.3 Define Flux HelmRepository resources and pin compatible chart versions for Grafana Operator, Grafana Alloy, Thanos, Loki, and any required Kubernetes metrics exporter.
- [x] 2.4 Add HelmRelease resources with dependency ordering, remediation, and conservative requests and limits for the single-node cluster.

## 3. Metrics platform

- [x] 3.1 Deploy Thanos Receive, Query, Store Gateway, and Compactor in single-replica mode with the Thanos object-store Secret, local-path PVCs, and a single-node receive hashring.
- [x] 3.2 Configure Thanos Query to query current samples from Receive and historical blocks from Store Gateway.
- [x] 3.3 Deploy the lightweight Kubernetes state and node metric sources required for node, workload, pod, and container visibility.
- [x] 3.4 Configure Alloy discovery, RBAC, scraping, relabeling, and remote write to Thanos Receive; expose Alloy delivery and scrape-failure metrics.
- [x] 3.5 Configure Thanos block retention from the agreed metrics retention value and ensure Azure lifecycle deletion is not enabled.
- [x] 3.6 Configure Alloy to scrape Grafana, Grafana Operator, Alloy, Thanos, and Loki metrics endpoints; verify that component scrape failures are queryable in Thanos.

## 4. Logs platform

- [x] 4.1 Deploy Loki as one monolithic replica with TSDB schema, a local-path working PVC, and the dedicated Loki Azure Blob Secret.
- [x] 4.2 Extend the Alloy DaemonSet with Kubernetes pod-log discovery and a Loki write pipeline using stable cluster, namespace, pod, and container labels.
- [x] 4.3 Configure Loki retention from the agreed logs retention value and keep the Loki Service internal to the cluster.

## 5. Grafana management and access

- [x] 5.1 Deploy Grafana Operator and wait for its CRDs and controller to become ready before applying Grafana custom resources.
- [x] 5.2 Declare the Grafana instance with a local-path PVC and the SOPS-encrypted administrator credential.
- [x] 5.3 Declare GrafanaDatasource resources for Thanos Query and Loki, using ClusterIP service URLs.
- [x] 5.4 Declare a baseline dashboard with cluster metrics and workload-log navigation.
- [x] 5.5 Extend the baseline dashboard with observability-platform health, resource usage, ingestion, and query-error panels.
- [x] 5.6 Pin the Loki and Thanos dashboard sources to the deployed component versions; generate or vendor only compatible open-source mixin dashboards and rewrite their datasource UIDs and cluster label variables.
- [x] 5.7 Provision the compatible Loki and Thanos dashboards through Grafana Operator in a dedicated observability folder; remove or disable panels that depend on undeployed components, unavailable recording rules, or incompatible labels, and document any omitted upstream dashboard.
- [x] 5.8 Declare a `GrafanaServiceAccount` for Grafana API automation, with the required role, an explicit token expiry, and an operator-generated Secret in `monitoring`.
- [x] 5.9 Create the Grafana Traefik Ingress at the chosen hostname with the `letsencrypt-production-azure` issuer; do not create Ingresses for Alloy, Thanos, Loki, or Grafana Operator.

## 6. Validation and rollout

- [x] 6.1 Verify every SOPS Secret is encrypted, no raw Azure credential is tracked by Git, and all Kustomizations render successfully.
- [x] 6.2 Reconcile the observability resources with Flux CLI and require the observability Kustomization and every related HelmRelease to report Ready.
- [x] 6.3 Use kubectl to verify required Pods, Services, PVCs, and the Grafana certificate are ready; diagnose and resolve every failed readiness condition.
- [x] 6.4 Verify Azure Blob receives Thanos blocks and Loki data using the two intended private containers.
- [x] 6.5 Emit a uniquely identifiable test log line from a Kubernetes workload and wait until Alloy has delivered it to Loki.
- [x] 6.6 Use the operator-generated Grafana service-account token, without printing it, to verify Grafana HTTPS API health and submit PromQL and LogQL requests through `POST /api/ds/query`.
- [x] 6.7 Require the Grafana API response to contain Thanos PromQL data and the emitted Loki log line; treat empty, error, or direct-backend-only results as a failed acceptance test.
- [x] 6.8 Verify the provisioned Loki and Thanos dashboard UIDs/titles through the Grafana API and run representative dashboard queries through Grafana; fail validation for broken datasource references or empty/error panel queries.
- [x] 6.9 Confirm the service token is absent from Git-tracked files and inspect node CPU and memory; tune limits or pause additional observability features if the single node enters memory pressure.
- [x] 6.10 Mark the change complete only after every preceding task has passed against the live cluster and the end-to-end Grafana API acceptance test succeeds.
