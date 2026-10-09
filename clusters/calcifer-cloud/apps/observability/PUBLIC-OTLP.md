# Public OTLP ingestion — `calcifer-cloud`

> **Status: live on 2026-10-09.** Flux reconciled public rollout commit `f6a9fbc`;
> the Certificate is Ready and HTTPS hostname/CA verification passed. Both server
> keys work for all three signals; missing/invalid keys and private paths are
> rejected. Live receiver/management isolation and tiny backend-storage/identity
> checks passed. No rejected custom ingestion limits were added. Isolated
> late-span/cache and queued-retry acceptance also passed. Real SDK integration,
> GitHub workflow execution and production load sizing remain separate.

## Endpoint and client setup

The endpoint is `https://otlp.calcifer.tech`. The interface
is OTLP/HTTP only: POST protobuf or JSON payloads to `/v1/traces`, `/v1/logs`, or
`/v1/metrics`. OTLP/gRPC, query APIs, and collector administration are not exposed.
Set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` explicitly. Use the
base endpoint for generic SDK configuration; per-signal endpoints must include
their corresponding `/v1/<signal>` path.

The gateway receivers and `vmauth` ingestion service are private ClusterIP
services. Their health/metrics endpoints (Alloy `12345`, `vmauth` `8428`) are
internal-only and scraped by the existing Alloy; they are not part of the public
route.

Two independent bearer credentials identify the clients:

| Client | Server-derived identity | Private Alloy receiver |
| --- | --- | --- |
| Local | `peer-reviewer-local` | port `4318` |
| GitHub Actions | `peer-reviewer-github` | port `4319` |

Provide an Authorization Bearer header by deriving its value at runtime from
`CALCIFER_OTLP_API_KEY`, using the SDK's standard `OTEL_EXPORTER_OTLP_HEADERS`
setting and its documented header syntax (or the SDK header API). Do not put a
literal key in source, print the header or environment, or enable logging that
could expose it. Set
`OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=cumulative`; check the actual
SDK/exporter version because support and behavior are SDK-version dependent.

The local key is for local development. The GitHub repository secret is only for
trusted branches and workflows; do not make it available to fork pull requests or
untrusted workflow code.

## Attribution and trace sampling

The gateway overwrites client-supplied identity with the authenticated alias in
`calcifer.ingest.client` and routes each alias through its own trace sampler, with
a distinct hash salt. This is attribution, not storage or query isolation.
For metrics, the trusted resource identity is also copied to datapoint attributes
so it survives VictoriaMetrics conversion. The current backend preserves its
dotted label name; Prometheus-name conversion settings can normalize it later.

To request a sampling exemption, set `calcifer.sampling.keep` to the **boolean**
`true` on a resource or span, early enough to reach the gateway before its tail
sampling decision. Do not encode it as the string `"true"` or a comma-separated
resource environment string. SDK head sampling must be `always_on` (export all
spans); spans discarded by the SDK or a marker arriving after the gateway's
decision cannot be recovered. A timely true marker keeps the received trace as a
unit. Other traces are sampled probabilistically at 50% (not an exact per-request
quota); logs and metrics bypass trace sampling.

The deployed Alloy configuration omits custom tail-sampler sizing. With pinned
Alloy `v1.19.2`, upstream defaults are a 30-second decision wait and 50,000 trace
slots **per alias sampler**; decision caches are inactive by default. These are
inherited defaults, not a measured client-capacity budget. Isolated tests with
the deployed configuration unchanged verified that, while the decision remains
resident, late spans of kept traces are forwarded and late spans/true markers of
dropped traces stay dropped. Once an ID is evicted from the resident buffer,
later spans can form a new sampling decision; this never recovers old discarded
spans or guarantees the original force-keep decision survives eviction. A small
test-only buffer verified this boundary and pending overflow. Restart loses
in-memory sampler state; force-keep is not a losslessness guarantee.

## Capacity and retention

The gateway memory limiter is configured with a 384 MiB hard limit and a 64 MiB
spike allowance (320 MiB soft threshold). Its per-alias processors observe the
same process memory; these are not separate memory pools. The Alloy container
limit is 512 MiB. `vmauth` has a separate 128 MiB container limit (32 MiB request).
These memory settings do not set a request-rate or request-size limit.

No custom request-size, request-rate/burst, concurrency, tail-buffer/cache,
exporter-queue, batching, or retry limits have been approved or configured.
Each Alloy exporter inherits pinned `v1.19.2` defaults, including an in-memory
1,000-request queue, 10 consumers, and a five-minute retry expiry; the
configuration does not select these as a measured capacity budget. With the
pinned `vmauth:v1.153.0` image and no explicit overrides, its source defaults are
100 concurrent requests per user, 1,000 globally, and a 10-second queue duration.
Those defaults are likewise not a measured public-ingestion capacity budget.
An isolated temporary log-backend outage using the unchanged gateway defaults
recovered all 20 accepted records. This is a bounded retry acceptance check, not
a claim about indefinite outages, restart persistence or production saturation.
Do not infer a safe request ceiling, no-loss behavior, or availability promise
from these defaults. The user directed public rollout with these inherited
defaults unchanged; custom tuning awaits client measurements and approval.

VictoriaLogs and VictoriaTraces use `6M` native fixed-duration
retention; VictoriaMetrics remains at 365 days. The application cleanup
caps are 8 GiB for logs and 20 GiB for traces. Existing PVC requests remain
unchanged: 8 GiB for logs and 10 GiB for traces, both using `local-path`. This
StorageClass uses shared node storage rather than enforcing a per-claim filesystem
quota. Disk pressure can cause earlier deletion than the age setting; `6M` is not
a minimum-history guarantee. Trace disk usage was not measurable when the cap was
selected, so 20 GiB is an allowance, not a stored-size forecast. These retention
and cap settings were verified on the live StatefulSets after Flux reconciliation.

The isolated log-backend cap test removed the oldest of three daily partitions
while preserving the latest two, even above a tiny test target: cleanup is not a
hard quota. Artificial free-space pressure rejected valid protobuf writes with
retryable HTTP 429. A frontend success still does not guarantee eventual storage
if exporter retries expire or the process restarts. Monitor read-only state and
terminal export failures as well as free bytes.

## Credentials and rotation

The two independent keys were provisioned by
[`scripts/provision-public-otlp-keys.py`](../../../../scripts/provision-public-otlp-keys.py).
The script refuses to overwrite an existing destination or backup. The server
configuration is stored in the SOPS-encrypted `public-otlp-auth` Secret and
mounted by `vmauth`. The local export is in `/home/dmgiangi/.bashrc` (`0600`),
with `/home/dmgiangi/.bashrc.before-calcifer-otlp` also set to `0600`. The
GitHub secret was set for `dmgiangi/peer-reviewer` using `gh` stdin; GitHub
permits metadata checks but not reading the secret value back. No key values are
recorded here or displayed for verification. Do not source `.bashrc` merely to
inspect the key.

To rotate or revoke one client, edit only that client's `bearer_token` entry in
the SOPS-managed auth configuration and coordinate the corresponding delivery
(`.bashrc` for local, or the repository Actions secret for GitHub). Do not rerun
the provisioning script, print a key/header, or remove the owner-only backup. Once
Flux has updated the Secret projection, `vmauth` polls its auth file every 10
seconds (`-configCheckInterval=10s`); if a change is not reloaded, use an approved
restart of only the `vmauth` Deployment. This reload path has not yet been
verified in a live rollout.

## Validation and opening the route

Safe local checks use synthetic credentials and Docker-local storage. They do
not send telemetry to the production cluster:

- `python3 -m unittest scripts.tests.test_public_otlp_keys` — 7 tests passed.
- `docker run --rm -v "$PWD/clusters/calcifer-cloud/apps/observability:/config:ro" grafana/alloy:v1.19.2 validate /config/otlp-gateway.alloy` — passed.
- `python3 scripts/tests/check_public_otlp_manifests.py` — rendering/references,
  receiver policy structure and preservation of existing configuration passed.
- `python3 scripts/tests/check_public_otlp_runtime.py` — pinned vmauth/Alloy and
  all three Victoria backends passed authentication, independent revocation,
  stored attribution, unsampled logs/metrics and whole-trace boolean/50% tests.
  The test starts bounded disposable containers with loopback-only query ports,
  then removes its own containers/network and temporary synthetic auth file.
- `python3 -B -m unittest discover -s scripts/tests -p 'test_public_otlp*.py'` —
  28 provisioning, live opt-in/cluster and sandbox-isolation unit tests passed.
- `python3 -B scripts/tests/check_public_otlp_advanced.py` extends acceptance with
  default late spans/resident decisions and exporter retry, plus accelerated
  test-only buffer/queue overflow, memory refusal and disk-pressure/cap stages.
  The final full run passed; no production saturation was exercised.
  Synthetic credentials remain in an owner-only mounted file; subprocess output,
  response bodies and container logs
  are captured. Containers have explicit CPU/RAM/tmpfs/time bounds and cleanup
  uses only their exact owned IDs. No production saturation test is implied.

Actual encrypted credentials also passed the pinned vmauth dry-run via stdin,
with output suppressed; gateway/route/policy schemas passed Kubernetes server
dry-run. `check_public_otlp_live.py --network-only` confirms Traefik reaches auth
but cannot bypass it to receivers or management ports; authorized scraper health
controls pass. Public TLS/real-server-key acceptance passed after Flux using
`check_public_otlp_live.py --accept-live-telemetry`. This explicit opt-in test
writes only two logs, two gauge points and two two-span force-kept traces, uses
loopback-only private query tunnels, and prints pass/fail without credentials or
payloads. It does not rotate/revoke keys or modify client workflows.

Validate real SDK temporality, span arrival and production capacity separately.
None is claimed by the live smoke test or bounded isolated acceptance. In
particular, testing the GitHub server credential does not prove a GitHub Actions
workflow or SDK is configured; those client changes remain outside this rollout.

## Monitoring and regression status

The private Grafana dashboard **Public OTLP Ingestion** (`public-otlp`) has
13 panels covering scrape health, authentication failures, sampling decisions,
resident IDs/early buffer loss, queues/in-flight requests, refusals/exporter
failures, process RSS and backend disk size/free space/read-only pressure. Its
14 queries were validated against live metrics. Failure series may be absent
until an event occurs; missing scrape data is never evidence of zero traffic.
Process RSS is not the limiter's Go heap measurement or container working set.

Read-only checks on 2026-10-09 verified fresh Cloud metrics/logs, HTTP 200 through
all three Grafana datasource proxies and the last two daily Velero backups
Completed with zero errors. No backup restore was run. Home collection is not
verified: its last metric is about 28 hours old, no metrics/logs arrived over
24 hours, and its API times out. That last sample predates public OTLP rollout;
the timing alone does not establish a cause. Home manifests and private Cloud
ingestion paths remain unchanged. Restore Home access/collection and obtain real
client arrival profiles before closing the remaining acceptance tasks.

Pinned references: [Alloy tail sampling](https://grafana.com/docs/alloy/v1.19/reference/components/otelcol/otelcol.processor.tail_sampling/),
[Alloy OTLP/HTTP exporter](https://grafana.com/docs/alloy/v1.19/reference/components/otelcol/otelcol.exporter.otlphttp/),
and [vmauth v1.153.0 source defaults](https://github.com/VictoriaMetrics/VictoriaMetrics/blob/v1.153.0/app/vmauth/main.go).