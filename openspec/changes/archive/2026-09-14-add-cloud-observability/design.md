## Context

`calcifer-cloud` is a single-node K3s cluster with 4 vCPU, roughly 4 GiB of allocatable memory, Flux, Traefik, cert-manager, and the `local-path` StorageClass. It has no workload observability services. The prior Mimir/Tempo/Redpanda design is too large for this topology.

Azure Blob Storage is available in `rg-calcifer-westeurope-001` through the LRS `calciferobs` account in `westeurope`. Its data-plane firewall permits the VPS egress IP `136.144.222.128`. The existing SOPS setup encrypts Kubernetes Secret `data` and `stringData` fields with age. A sanitized Thanos object-store Secret template already exists but is not referenced by Flux.

The operator has authenticated Flux and kubectl CLI access to `calcifer-cloud`. This permits reconciliation, readiness inspection, and runtime verification, but does not remove the requirement to validate the end-user query path through Grafana.

## Goals / Non-Goals

**Goals:**

- Provide PromQL metrics and LogQL logs in a Grafana UI managed declaratively through Grafana Operator.
- Preserve historical metrics and logs in private Azure Blob containers while keeping only active working data on local-path PVCs.
- Fit a non-HA, resource-bounded deployment on the current single-node cluster.
- Keep credentials encrypted in Git and expose only Grafana through HTTPS.

**Non-Goals:**

- High availability, multi-zone replication, remote write buffering, Redpanda, Tempo, tracing, profiling, or Mimir.
- Public exposure of Thanos, Loki, Azure Blob, or the Grafana Operator API.
- Alert delivery integration until a notification target and retention policy are agreed.

## Decisions

### Use Thanos Receive as the metrics write path

Grafana Alloy will discover and scrape Kubernetes metrics, then use Prometheus remote write to a single Thanos Receive instance. Thanos Query will be Grafana's Prometheus-compatible datasource and will query Receive for current samples plus Store Gateway for Azure-resident blocks. Compactor owns block compaction and retention.

This retains PromQL and Blob persistence without Mimir's distributed runtime. Prometheus plus a Thanos Sidecar was considered, but it creates a separate Prometheus server and makes the fresh-data query path more cumbersome. VictoriaMetrics single-node was considered, but its primary persistence is local rather than Azure Blob.

### Use Loki in monolithic mode

Alloy will run as a DaemonSet to read pod logs and forward them to one monolithic Loki replica. Loki will use TSDB mode and Azure Blob as its durable store; a local-path PVC holds WAL and transient working data.

The distributed and simple-scalable Loki modes are excluded because the cluster has only one node. Monolithic Loki is sufficient for the expected small log volume and can later be migrated to a distributed layout using the same object-store data.

### Use separate private containers and least-privilege credentials

Thanos will use the existing private `thanos` container with `calcifer-cloud` as its object prefix. Loki will use a separate private `loki` container. Each backend will receive a separate least-privilege credential through its own SOPS-encrypted Secret.

The pinned Loki Azure client has an upstream bug when SAS is supplied through a connection string: it adds an invalid shared-key authorization header and constructs requests at the account root. Thanos retains its working container-scoped SAS; Loki therefore uses a dedicated Azure service principal whose `Storage Blob Data Contributor` role is scoped only to the `loki` container. Both credentials remain in dedicated SOPS-encrypted Secrets.

Using one account-wide key or one shared container would reduce setup work but would widen blast radius. Azure account keys, raw SAS tokens, and service-principal secrets MUST NOT be committed or sent in chat. Azure Blob retains the Hot tier initially; lifecycle tiering is deferred until compaction and access patterns are measured.

### Manage components through Flux and Helm; manage Grafana resources through Grafana Operator

The implementation will add an `apps/observability` Kustomize root included by the existing Flux `cloud-apps` Kustomization. HelmRelease resources will install the Grafana Operator, Alloy, Thanos, and Loki from pinned chart versions. Grafana, datasource, and dashboard custom resources will be managed by Grafana Operator.

All backend Services remain ClusterIP. Grafana alone will receive a Traefik Ingress with a cert-manager production certificate and a SOPS-encrypted administrator credential. The Grafana hostname is a deployment value, with `grafana.calcifer.tech` as the intended default.

### Provision version-matched open-source dashboards

The implementation will include operational dashboards from the upstream open-source monitoring mixins when they are compatible with the pinned backend versions: the Loki mixin dashboards from the matching Loki release and the Thanos mixin dashboards from the matching Thanos release. The Thanos project documents and maintains its mixin examples, and the Grafana monitoring-mixins directory lists both Thanos and Loki sources.

Dashboard JSON will be generated or vendored during implementation rather than downloaded by a running Pod. Datasource references will be rewritten to the stable Grafana Operator datasource UIDs, and variables/queries will be adapted to the labels emitted by this cluster. Panels depending on components not deployed here (for example Loki Canary or unavailable recording rules) will be removed or disabled. If an upstream dashboard is unavailable or incompatible, the baseline self-monitoring dashboard remains the required fallback and the incompatibility is recorded in the change validation.

The dashboards will be placed in a dedicated Grafana folder and provisioned through Grafana Operator resources. Dashboard presence and queryability are part of the live Grafana API acceptance check; manual UI imports are not allowed.

### Generate separate Grafana admin and API credentials

The local Grafana `admin` username will be configured from a SOPS-encrypted Secret. Its password will be generated with a cryptographically secure local generator during implementation; SOPS encrypts the resulting value but is not itself a random-password generator. The plaintext password MUST NOT be printed by automation or committed to Git.

A `GrafanaServiceAccount` custom resource will create a distinct `grafana-api` service account with the minimum Grafana role required for API automation, initially `Admin` for datasource and dashboard management. Grafana Operator will generate its token and write it to a Kubernetes Secret in `monitoring`; the token Secret is runtime state and MUST NOT be declared or encrypted in Git. The service-account token MUST have an explicit expiry and a documented rotation task.

### Treat the observability platform as a monitored workload

Alloy will scrape the metrics endpoints of Alloy itself, Grafana, Grafana Operator, every Thanos component, and Loki. A declarative dashboard will show backend availability, pod resource consumption, scrape and remote-write health, Thanos block and query activity, and Loki ingestion/query errors. This meta-monitoring uses the same non-HA pipeline, so it reports failures but cannot observe a total cluster outage.

### Require live end-to-end acceptance before completion

The implementation is complete only after Flux reports the observability Kustomization and all related HelmReleases Ready, kubectl confirms that required Pods, Services, PVCs, and the Grafana certificate are ready, and the Grafana service-account token succeeds against the HTTPS API.

The final query check MUST use Grafana's datasource query API (`POST /api/ds/query`) with the service-account Bearer token, rather than calling Thanos or Loki directly. It will execute a PromQL health query that returns data from Thanos and a LogQL query that returns a deliberately emitted workload log from Loki. The validation command MUST not print the token. A failed readiness check, HTTP API call, datasource query, or empty expected result leaves the change incomplete.

### Bound resources and keep local state disposable

Every workload will define CPU and memory requests and limits sized for the single node. Thanos Receive and Loki will use local-path PVCs for data not yet uploaded and for WAL/index work. Azure Blob is the durable source for historical data; local PVC loss can create a recent-data gap but does not affect uploaded blocks.

## Risks / Trade-offs

- [Single node failure makes recent metrics and logs temporarily unavailable] → Use Azure Blob for historical blocks, keep local PVC requests explicit, and document the non-HA service level.
- [Combined observability workloads exceed node memory] → Set conservative limits, deploy incrementally, inspect `kubectl top` after each release, and halt rollout if the node approaches memory pressure.
- [A leaked storage credential exposes telemetry] → Use separate container-scoped credentials, IP restriction, finite service-principal credential lifetime, SOPS encryption, and no plaintext secrets in Git.
- [Incorrect Loki labels cause high cardinality] → Restrict labels to stable Kubernetes identity fields and avoid arbitrary pod annotations or log attributes.
- [Azure lifecycle policy conflicts with compaction or retention] → Keep the Hot tier and no automatic tier/delete policy until telemetry volume is known.
- [A chart upgrade changes values or CRDs] → Pin chart versions, render manifests before commit, and use HelmRelease remediation settings.
- [A service-account token leaks or expires unexpectedly] → Store it only in the operator-generated Secret, use a finite expiry, restrict Kubernetes Secret access, and rotate it before expiry.
- [The monitoring pipeline cannot observe its own total failure] → Expose self-health in Grafana and retain Kubernetes/Flux readiness checks as an out-of-band operational verification.

## Migration Plan

1. Create the private `thanos` and `loki` containers and generate the Thanos scoped SAS plus the Loki container-scoped service principal after rotating any previously exposed storage account keys.
2. Update the encrypted object-store Secrets locally with SOPS; do not commit a raw credential.
3. Add Flux manifests and deploy the namespace, operator, metrics path, logs path, and Grafana in dependency order.
4. Reconcile with Flux, inspect live state through kubectl, then validate Grafana HTTPS and PromQL/LogQL results via the Grafana service-account API without writing the token to terminal output.
5. Roll back by removing the observability Kustomize resource from `apps`; Flux prunes Kubernetes resources while Azure Blob data remains intact until an explicit retention/deletion decision.

## Open Questions

- Confirm the Grafana hostname and the administrator's initial access method.
- Choose metrics and logs retention periods before enabling compactor/Loki retention.
- Confirm whether dashboards and alert rules are in scope for the first implementation, and identify an alert notification destination if they are.
