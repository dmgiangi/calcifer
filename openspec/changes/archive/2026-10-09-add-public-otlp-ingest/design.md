## Context

`calcifer-cloud` uses K3s, Flux, Traefik, Grafana Alloy, and single-node Victoria
backends. Existing Alloy runs as a DaemonSet, receives private traces on port
4318, scrapes metrics, and collects workload logs. VictoriaMetrics retains metrics
for 365 days, VictoriaLogs retains logs for 14 days, and VictoriaTraces retains
traces for 7 days. Logs and traces have disk-usage cleanup limits independent of
their age-based retention.

Two external clients require distinct credentials: `peer-reviewer-local` and
`peer-reviewer-github`. Both may request trace sampling exemption. The proposed
public hostname is `otlp.calcifer.tech`, approved for rollout on 2026-10-09 after
the public-opening clarification.

Retention, the private vmauth/Alloy gateway and NetworkPolicies are deployed via
Flux. Two independent keys have been encrypted and delivered to the local
startup file and GitHub secret. The public Certificate/IngressRoute is now
included for the authorized rollout, without custom ingestion limits. Local
Docker acceptance passed; live findings and deferred checks are in `preflight.md`.

## Goals / Non-Goals

**Goals**

- Authenticate public OTLP/HTTP writes for all three signals with independent,
  revocable keys and no credential exposure during generation or delivery.
- Keep marked traces and retain approximately 50% of other external traces.
- Configure six-month time retention for the existing log and trace backends.
- Preserve cluster collection, private Home ingestion, and existing Grafana access.

**Non-goals**

- Public OTLP/gRPC, collector UI, backend queries, Prometheus remote write, or
  Loki push through the new endpoint.
- Per-key retention, separate storage tenants, query authorization per client,
  Enterprise retention filters, or automatic installation of client SDKs.
- Adding errors/latency sampling exceptions beyond the explicitly requested flag.
- A lossless-ingestion or six-month minimum-storage SLA under resource exhaustion.

## Decisions

### 1. Public TLS endpoint with multi-key authentication

Use the proposed hostname with exact POST paths `/v1/traces`, `/v1/logs`, and
`/v1/metrics`. Traefik terminates TLS and proxies only ingestion traffic to
`vmauth`; unauthenticated, invalid, and revoked keys cannot reach Alloy.

`vmauth` supports multiple bearer users and path-restricted proxying. Each named
user is routed to a distinct private OTLP receiver on a dedicated Alloy gateway:
port 4318 for local and 4319 for GitHub. Neither receiver is exposed directly.
Remove the inbound Authorization header before forwarding. Keep gateway metrics,
health/admin endpoints, collector UI, and backend APIs private. Explicit Network
Policies restrict access to the private receivers to the authentication workload.

This is preferable to embedding key values in Traefik routing rules or adding a
custom authentication service. The user explicitly approved the pinned vmauth
v1.153.0 image; native Deployments reuse the existing Alloy v1.19.2 image.

### 2. Dedicated Alloy gateway and server-derived identity

Keep the existing Alloy DaemonSet unchanged and deploy a separate ingestion Alloy
Deployment with one replica and a non-overlapping rollout strategy. This avoids
splitting the spans of a trace across DaemonSet instances. Scaling later requires
trace-ID-aware routing, not ordinary Service round-robin distribution.

Each receiver applies its authenticated alias as the resource attribute
`calcifer.ingest.client`, overwriting any client-supplied value. Remove conflicting
record-level values, and verify the attribute remains queryable after export.
Use separate trace-sampling processor instances for the two receiver pipelines so
identical trace IDs from different clients cannot share a sampling decision.
This identity provides attribution, not storage/query isolation.

Forward logs to VictoriaLogs at `/insert/opentelemetry/v1/logs`, metrics to
VictoriaMetrics at `/opentelemetry/v1/metrics`, and traces to VictoriaTraces at
`/insert/opentelemetry/v1/traces`, using internal Service URLs. Verify supported
OTLP encoding and metric temporality with the pinned backend versions.

### 3. Force-keep contract and default trace sampling

Use `otelcol.processor.tail_sampling` with a `boolean_attribute` policy matching
`calcifer.sampling.keep=true` and a `probabilistic` policy with
`sampling_percentage=50`. Do not add inverted/drop policies that override the
force-keep decision. A true resource or span attribute observed before the
decision keeps the whole received trace within that authenticated client pipeline.
Absent, false, string `"true"`, or numeric values do not request exemption.

Clients must export the required spans rather than head-sampling them away. They
should set the boolean marker early and consistently on the relevant spans or
resource; marking only a late-ending root span is not a reliable exemption.
Choose custom decision-window, trace-buffer and sampled/non-sampled cache values
only after measuring client span arrival patterns and obtaining approval. The
user directed public rollout without the rejected guessed limits, so those
measurements are follow-up work, not public-opening gates. Late markers cannot
restore previously discarded spans. Document restart, overflow and late-span
behavior explicitly; no load or late-span acceptance is implied by opening TLS.

The deployed configuration omits custom sizing, inheriting Alloy's
30-second decision wait, 50000 trace slots per sampler and disabled decision
caches. Omission is not an unlimited sampler and does not mean these defaults
are a measured client capacity budget. They remain unchanged for the authorized
best-effort public rollout. Exporter/request defaults have the same status.

Isolated unchanged-default tests verify that resident kept decisions forward
late spans and resident dropped decisions reject late spans/force-keep markers.
With decision caches inactive, buffer eviction allows later spans to be evaluated
again; this cannot recover previously discarded spans or preserve a lost marker.
An explicitly test-only small buffer demonstrated this boundary and early loss.

The 50% rate is probabilistic across traces, not an exact count per request or
sampling of half the spans in each trace. Logs and metrics bypass trace sampling.
The marker never bypasses authentication, overload controls, or backend retention.

### 4. Shared backends with six-month time retention

Interpret the requested retention increase as a change to the existing shared
VictoriaLogs and VictoriaTraces instances, including infrastructure data. Set
both to `6M` using their native fixed-duration month semantics, not a claim about
calendar-month expiration. Keep VictoriaMetrics at `365d`. This is a common policy
for both external aliases, not retention derived from the credential itself.

Keep existing data and PVCs: no deletion, recreation, or recovery of expired data
is part of this change. Review disk capacity and existing cleanup caps before
deployment; any storage expansion or cost-bearing capacity change requires an
explicit approved plan. Time retention is an upper age horizon and disk pressure
can cause earlier deletion. Monitor free space, oldest data age, and cleanup.

The 2026-10-08 capacity review measured about 89 MiB of compressed log storage
over a 14.9-day timestamp span, projecting to about 1.05 GiB over 180 days. Keep
the existing 8 GiB log cleanup cap, which already exceeds twice that estimate.
VictoriaTraces had zero persisted bytes at measurement time, so an on-disk
six-month estimate was unavailable. Its OTLP input counter projects to about
9.2 GiB of raw ingress over 180 days; set the application cleanup cap to 20 GiB,
more than twice that ingress projection, rounded up. This is a capacity allowance
based on wire bytes, not a measured stored-size forecast, and does not guarantee
six months of trace history.

The 10 GiB VictoriaTraces PVC request remains unchanged because the `local-path`
StorageClass does not support volume expansion. The local-path volume exposes the
shared node filesystem rather than enforcing a per-claim filesystem quota; the
20 GiB application cap therefore relies on host capacity. The node had about
121 GiB free at measurement time, enough for the combined 28 GiB backend caps,
subject to growth from other workloads.

### 5. Secret generation and delivery without observation

Generate two independent tokens using at least 32 cryptographically random bytes
each, with safe text encoding. Plaintext remains only in process memory, stdin,
or restricted-permission temporary files. Never print it, return it to agent
tools, pass it via argv/URLs, or include it in shell tracing, history, or telemetry.

Encrypt a Kubernetes Secret/auth configuration under the existing SOPS rules
before adding it to Git. Validate decrypted configuration internally without
printing it. Mount it as a Secret rather than placing keys in ConfigMaps or
HelmRelease inline values. Confirm auth reload/restart behavior for key revocation.

Deliver the local token through a managed `export CALCIFER_OTLP_API_KEY` entry in
the explicitly requested `/home/dmgiangi/.bashrc`, without displaying its contents.
Preserve unrelated lines and use owner-only permissions; any backup also needs
owner-only permissions. This is a plaintext credential at rest by the user's
request; a shell entry sourcing a separate mode-0600 credential file is a safer
alternative if approved. Do not source `.bashrc` merely to inspect the token.

For GitHub, use `gh secret set CALCIFER_OTLP_API_KEY --repo dmgiangi/peer-reviewer`
with the token supplied through stdin, never through `--body` or another argv
value. Check existing secret names and local entry presence without printing
values, and ask before replacing an existing credential. Verify only secret
metadata and pass/fail results. Each destination receives only its own token.

## Risks / Trade-offs

- Force-keep may increase storage load: both authenticated clients may use it on
  all traces. Request and memory limits remain effective regardless of the flag.
- Single-replica tail sampling is simple but has a restart/availability boundary;
  in-memory traces can be lost, and queued exporters do not persist sampler state.
- Longer retention on small disks may not yield six months of actual history.
- A GitHub repository secret does not automatically configure workflow/SDK use;
  client changes are separate, and untrusted fork workflows must not receive it.
- Literal shell exports can be exposed by later user commands or child processes;
  clients and validation commands must avoid dumping environment or auth headers.

## Migration Plan

1. Approve hostname, new dependency, retention scope, and resource/capacity bounds.
2. Generate/encrypt/deliver the credentials safely during implementation.
3. Add the protected gateway, signal pipelines, TLS, and Network Policies through
   Flux, leaving existing collection/private paths intact.
4. Increase time-retention settings without recreating backend volumes.
5. Validate authentication for both aliases, spoof resistance, all three signals,
   force-keep and 50% sampling, safety limits, and existing ingestion regressions.
6. Roll back by disabling only the new public route/workloads. Reverting retention
   to shorter values can delete newly retained history and needs explicit approval.

## Open Questions / Approval Gates

- The user approved vmauth `v1.153.0` and the proposed memory requests/limits on
  2026-10-09, then instructed public opening on `otlp.calcifer.tech` following
  the explicit clarification, without adding the rejected custom limits.
- Shared six-month retention and the 8/20 GiB application caps were approved;
  existing PVC requests and shared-backend scope remain unchanged.
- Measure client traffic/span arrival patterns before proposing custom request
  size/rate, concurrency, decision timing, trace-buffer/cache and queue bounds.
  This is deferred tuning work, not an opening gate. Do not invent values or
  infer headroom from retention.
- OpenSpec CLI strict validation, pinned image validators, local Docker
  acceptance and Kubernetes server dry-run passed. Advanced isolated checks passed
  default late-span/retry behavior, accelerated buffer/queue overflow, memory 503
  refusals (including force-keep), backend disk-pressure 429 refusals using valid
  protobuf, and oldest-partition disk-cap cleanup preserving the latest two days.
  Reduced bounds exercise mechanisms, not production saturation. Real client
  arrival profiles remain unavailable and Home continuity is blocked by stale
  collection and API timeouts. Private/public Flux rollouts, public TLS, live
  isolation and tiny identity/storage smoke checks passed. See `preflight.md`.

## References

- https://github.com/grafana/helm-charts/releases/tag/alloy-1.12.1
- https://github.com/grafana/alloy/blob/v1.19.2/docs/sources/reference/components/otelcol/otelcol.processor.tail_sampling.md
- https://docs.victoriametrics.com/vmauth/
