# Public OTLP implementation preflight

Read-only checks performed on 2026-10-08 against `calcifer-cloud`. No new
dependencies, credentials, deployments, or Git commits were created during that
preflight. Implementation/credential provisioning and local checks on 2026-10-09
are recorded below. Commit `a7f263c` was pushed and the private gateway reconciled
on 2026-10-09. The subsequent instruction to proceed after the public-opening
clarification authorizes the public route without adding the rejected custom
limits; public rollout and acceptance are recorded separately below.

## Confirmed prerequisites

- `otlp.calcifer.tech` resolves to `136.144.222.128`; the subsequent instruction
  to proceed after the public-opening clarification completes task 1.1.
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
client measurements. The subsequent instruction authorizes best-effort public
rollout with inherited pinned defaults unchanged; measurements and custom tuning
remain follow-up work, not public-opening gates. The `v1.153.0`
version is the official non-prerelease release published on 2026-09-28.

## Private implementation and validation — 2026-10-09

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

## Public rollout — 2026-10-09

- The user instructed “procedi” after the explicit public-opening clarification.
  `public-otlp-ingress.yaml` is included for rollout on `otlp.calcifer.tech`.
  No rejected custom rate/size/timing/buffer/queue limits were introduced.
- Pre-opening isolation probes passed: Traefik reaches vmauth (401 without a
  key), but receiver ports 4318/4319 and management ports 8428/12345 reject its
  connections. Authorized existing Alloy scraper health controls return 200.
  K3s denies these connections with refusal, not necessarily a timeout.
- At this check, gateway/vmauth memory is 44/5 MiB and node MemoryPressure and
  DiskPressure are False. This idle snapshot is not a load-capacity measurement.
- Updated manifest contracts, seven credential unit tests, Cloud rendering,
  route/Certificate server dry-run, strict OpenSpec validation and IDE build pass.
- Public commit `f6a9fbc0fb92d13257f3af5de30f5c6cd4794f8e` was pushed and
  reconciled by Flux `cloud-apps` (Ready). Certificate `public-otlp` is Ready,
  valid through 2027-01-07T05:32:11Z; default HTTPS hostname/CA checks passed.
- `check_public_otlp_live.py --accept-live-telemetry` passed: both real server
  keys accept all three signal paths, missing/invalid keys return 401, and
  management/query/unsupported paths and GET return 404.
- Two synthetic logs, two gauge points and two two-span boolean-force-kept
  traces were queried privately in their backends. Both aliases overwrite spoofed
  identity; captured recent gateway/Traefik logs contain no server key or test
  payload marker. No credentials/headers/payloads were printed or passed in argv.
  These tiny synthetic records remain stored under normal retention.
- Live isolation passed again after opening. Gateway/vmauth remain 1/1 and
  use about 45/5 MiB at idle. All other monitoring workloads remain Ready; the
  Velero daily schedule is Enabled with its last backup at 2026-10-09T02:00:40Z.
- Eleven unit tests pass, including four new fail-closed cluster/opt-in guards.
  Real SDK/GitHub workflows, live key revocation, late spans and queue/resource/
  storage-pressure tests remain deferred. No six-month minimum-history or
  validated load-capacity claim is made.

## Advanced acceptance and read-only regressions — 2026-10-09

- The unchanged production-default sampler passed kept/dropped late-span tests
  for both aliases, including late boolean force-keep on previously dropped
  traces. While a decision remains resident, kept traces receive their late spans
  and dropped traces stay dropped; a late marker does not resurrect old spans.
- The unchanged exporter configuration passed a disposable backend outage:
  20 accepted log records were held queued/in-flight and subsequently recovered,
  with no terminal send/enqueue losses. Docker may remap ephemeral query ports
  on restart; the acceptance harness now refreshes those mappings.
- Fresh trace search visibility in pinned VictoriaTraces is delayed by about
  30 seconds independently of tail sampling. Accelerated tests must wait for
  backend visibility, not infer loss from an earlier negative query.
- All 28 provisioning/live-guard/isolation unit tests pass. The base pinned-image
  Docker acceptance, updated manifest contract, IDE build, OpenSpec strict and
  repeated read-only live receiver/management isolation checks also pass.
- Additional explicitly accelerated sandbox stages passed: an 8-slot sampler
  evicted a pending trace and re-evaluated evicted IDs without recovering old
  spans; a 2-request exporter queue recovered 2 accepted records and rejected 18;
  reduced memory produced retryable 503 for logs and force-kept spans from both
  aliases, with matching pinned memory-limiter counters and no OOM.
- Direct VictoriaLogs disk checks use a minimal synthetic protobuf fixture,
  matching the real exporter encoding. Under artificial free-space pressure,
  valid writes returned retryable 429 and were not stored. JSON is accepted by
  the Alloy receiver, not this backend API; a direct JSON probe would return 400
  for invalid encoding and cannot validate the storage guard.
- A 1-byte TEST-ONLY disk target evicted the oldest of three daily log partitions
  but preserved the latest two even above its target. This is not a hard quota;
  application caps can shorten retained history without guaranteeing a ceiling.
  The final full `check_public_otlp_advanced.py` run passed every stage, captured
  credential/body log guards and exact-owned-ID cleanup. No production stress
  or disk changes were made.
- A private Grafana dashboard contains 13 panels for auth errors, sampling,
  resident traces, early loss, queues/in-flight operations, refusals/exporter
  failures, process RSS and backend storage pressure. All 14 queries return
  valid results against the live metrics backend and server schema dry-run passes.
  Process RSS is used because container working-set series are not collected;
  these different memory measurements must not be equated.
- Dashboard/acceptance commit `2073086` was pushed and reconciled through
  `observability-grafana`. Both it and `cloud-apps` are Ready at that revision.
  The GrafanaDashboard reports DashboardSynchronized=True / ApplySuccessful;
  the private Grafana API returns the exact committed 13 panels and 14 queries.
  Existing gateway/vmauth pods predate the release, remain 1/1 and have zero
  restarts. No sampler/config change, credential rotation or Home edit was made.
- Progress is 25/27. Tasks 1.4 (real client arrival/capacity measurements) and
  5.6 (live Home continuity) remain explicitly blocked; do not archive as fully
  complete or replace these missing checks with synthetic evidence.
- Cloud collection remains fresh: metrics about 6–8 seconds old, 672 logs over
  15 minutes and 77353 over 24 hours at the measured snapshot. All three Grafana
  datasource proxies return HTTP 200 using an existing service-account credential
  internally, never emitting auth or response bodies.
- The October 8 and 9 daily Velero backups are Completed with zero errors. No
  restore job was launched and no restore-success claim is made.
- Home has no recent metrics/logs over 24 hours. Its last metric sample is about
  28 hours old, predating public OTLP rollout; this timing does not prove a cause.
  Read-only Home API requests time out. End-to-end Home continuity remains blocked,
  even though Home manifests and existing Cloud ingestion paths are unchanged.
- Node filesystem has 145.2 GiB total / 121.0 GiB available; kubelet reports about
  3001 MiB available memory and both pressure conditions are False. Backend read-only
  counters are zero and effective application caps remain 8/20 GiB. This is a
  point-in-time headroom check, not a measured external-client capacity budget.
- Real local/GitHub SDK span-arrival profiles remain unavailable. Neither these
  synthetic tests nor successful server-key authentication proves SDK/workflow
  integration. Task 1.4 remains open; no rejected tuning values are introduced.

## Evidence sources

- https://github.com/grafana/helm-charts/releases/tag/alloy-1.12.1
- https://github.com/grafana/alloy/blob/v1.19.2/docs/sources/reference/components/otelcol/otelcol.processor.tail_sampling.md
- https://github.com/VictoriaMetrics/helm-charts/releases/tag/victoria-logs-single-0.13.9
- https://github.com/VictoriaMetrics/helm-charts/releases/tag/victoria-traces-single-0.1.11
- https://github.com/VictoriaMetrics/VictoriaMetrics/releases/tag/v1.153.0
- https://docs.victoriametrics.com/vmauth/