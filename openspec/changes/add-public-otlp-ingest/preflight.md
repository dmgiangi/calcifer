# Public OTLP implementation preflight

Read-only checks performed on 2026-10-08 against `calcifer-cloud`. No new
dependencies, credentials, deployments, or Git commits were created during that
preflight. Implementation/credential provisioning and local checks on 2026-10-09
are recorded below. Commit `a7f263c` was pushed and the private gateway reconciled
on 2026-10-09; the public route remains excluded and disabled.

## Confirmed prerequisites

- `otlp.calcifer.tech` resolves to `136.144.222.128`; explicit hostname approval
  remains task 1.1, even though DNS is present.
- The local `CALCIFER_OTLP_API_KEY` entry and named GitHub repository secret are
  absent at preflight. `gh` reports push/admin permissions for
  `dmgiangi/peer-reviewer`. Absence was rechecked before provisioning on 2026-10-09;
  both destinations now contain their own independent key. Never overwrite silently.
- SOPS tooling is available. Decrypting an existing Cloud Secret with all output
  suppressed succeeded. Flux `cloud-apps` uses SOPS with `sops-age` and is Ready.
- Existing ingress uses Traefik `websecure`, cert-manager issuer
  `letsencrypt-production-azure`, and Secret-backed TLS certificates.
- All three Victoria backends have ingress NetworkPolicies. They currently allow
  the existing Alloy DaemonSet, Traefik, and Grafana. Add a narrowly selected new
  gateway peer without removing existing peers. Private receivers must be
  isolated from Traefik and ordinary workloads, allowing only vmauth.
- The official Alloy **chart** `1.12.1` has **appVersion `v1.19.2`**, matching the
  live Alloy image. Do not confuse chart version with runtime version.
- Live backend images are VictoriaMetrics `v1.151.0`, VictoriaLogs `v1.52.0`,
  and VictoriaTraces `v0.11.0`; their chart versions differ from these versions.
- Alloy `v1.19.2` documents boolean resource/span tail sampling and a separate
  probabilistic policy. A matching keep policy wins without inverted/drop
  policies. Separate alias samplers and different hash salts avoid mixed state.
- The pinned log/trace chart templates convert numeric disk bounds to GiB and
  pass explicitly unit-suffixed strings through unchanged. Existing `8GiB` and
  `20GiB` values are supported; no correction is needed.

## Capacity evidence and limitations

- Node filesystem: about 145.2 GiB total and 121.1 GiB available. Approved
  application caps remain 8 GiB logs and 20 GiB traces, with unchanged PVCs.
- `kubectl top nodes` reports about 4827 MiB working memory / 124% of allocatable
  memory. Kubelet concurrently reports about 3.03 GiB available, while Kubernetes
  allocatable memory is about 3.79 GiB. These counters disagree; the percentage
  alone does not establish an out-of-memory condition or sufficient headroom.
- Node MemoryPressure/DiskPressure are False and memory PSI averages are zero.
  Check scheduling requests and observed gateway memory before public rollout.
- Neither external client is configured yet, so its span arrival distribution
  has not been measured. Existing infrastructure ingress is not a substitute.
  Alloy's inherited 30-second window is not an approved client SLA; the private
  staging configuration does not override upstream sampler defaults.

## Explicit approvals and deferred limits

Approved by the user on 2026-10-09:

| Setting | Proposal |
| --- | --- |
| vmauth image | `victoriametrics/vmauth:v1.153.0` |
| Alloy memory | Request 256 MiB, container limit 512 MiB |
| vmauth memory | Request 32 MiB, container limit 128 MiB |

The following have **not** been approved and must not be configured from the
earlier guesses: maximum request size, request rate/burst, concurrency, tail
decision delay, per-client pending trace count, trace size, decision cache, and
exporter queue/batch/retry bounds. No traffic/span-arrival profile is available
to size these settings. Select and validate them with the user using relevant
client measurements. Do not expose or enable the public route until ingestion
limits and bounded sampler behavior are agreed and tested. The `v1.153.0`
version is the official non-prerelease release published on 2026-09-28.

## Implementation and validation — 2026-10-09

- Added private one-replica/Recreate Alloy and vmauth Deployments, split receiver
  identities and salted samplers, explicit protobuf signal exporters, separate
  internal scrape Services, receiver policies and additive backend policy peers.
- Credentials were generated in memory, encrypted/round-trip checked with SOPS,
  and delivered through a local mode-0600 export and GitHub stdin. No temporary
  plaintext files were used; an owner-only local startup-file backup is retained.
- Both pinned validators passed. Decrypted server auth was fed to vmauth through
  stdin with captured output, never printed or staged in plaintext.
- Seven provisioning unit tests, Cloud rendering/reference/regression checks,
  Kubernetes server dry-run for new workloads/TLS/policies, strict OpenSpec
  validation, whitespace checks and IDE build passed.
- Commit `a7f263c556fd8dee5f1015ad93d6b27b714824e2` was fetched and applied by
  Flux `cloud-apps`; its Ready revision matches that commit. Both private
  deployments are Ready 1/1, and all four ClusterIP services have ready endpoints.
- VictoriaLogs and VictoriaTraces HelmReleases report `UpgradeSucceeded`; their
  live StatefulSet flags are `retentionPeriod=6M` and disk caps `8GiB`/`20GiB`.
  PVC requests remain unchanged. The SOPS Secret exists; only metadata was read.
- The public `IngressRoute` and `Certificate` are absent from the cluster and
  their manifest remains excluded from the Flux Kustomization.
- Isolated Docker tests against the pinned Victoria images passed authentication
  for both aliases/all signals; missing/invalid/revoked keys; wrong routes/GET;
  stored identity/spoofing; span/resource force-keep; absent/false/string 50%
  populations; identical cross-client trace IDs; unsampled logs/gauge/cumulative
  metrics; independent revocation and a credential/payload log-disclosure check.
- Exporters share the same three internal backend URLs as configured. Metrics
  must be cumulative; no experimental delta-conversion component was introduced.
- vmauth version and RAM bounds are approved; task 1.1 remains open for explicit
  hostname approval. Task 1.4 remains open for measured/approved ingestion and
  sampling bounds.
- Public Certificate/IngressRoute is deliberately excluded from Kustomize. The
  private gateway is deployed; the public endpoint is not. No production
  telemetry test has run.
- Inherited request/sampler/exporter defaults are not newly approved settings;
  custom request/rate/timing/cache/queue values were not added. Memory limiter
  thresholds leave headroom below the approved Alloy container RAM limit.
- Live TLS/NetworkPolicy enforcement, production-key acceptance, late-span/cache
  behavior, observed resource pressure, client/load acceptance and post-rollout
  collection regressions remain pending. Flux reconciliation and effective
  retention were verified; no public endpoint has been enabled.

## Evidence sources

- https://github.com/grafana/helm-charts/releases/tag/alloy-1.12.1
- https://github.com/grafana/alloy/blob/v1.19.2/docs/sources/reference/components/otelcol/otelcol.processor.tail_sampling.md
- https://github.com/VictoriaMetrics/helm-charts/releases/tag/victoria-logs-single-0.13.9
- https://github.com/VictoriaMetrics/helm-charts/releases/tag/victoria-traces-single-0.1.11
- https://github.com/VictoriaMetrics/VictoriaMetrics/releases/tag/v1.153.0
- https://docs.victoriametrics.com/vmauth/