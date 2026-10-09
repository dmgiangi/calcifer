# Public OTLP ingestion — `calcifer-cloud`

> **Status: staged, not live.** Gateway, `vmauth`, and NetworkPolicy manifests
> are staged files, not a completed rollout. `public-otlp-ingress.yaml` is
> explicitly excluded from `kustomization.yaml`; the public route is closed.
> No rollout or public telemetry acceptance test has occurred. The proposed
> hostname also requires explicit approval before routing.

## Endpoint and client setup

The prospective endpoint is `https://otlp.calcifer.tech`. The intended interface
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

The staged Alloy configuration omits custom tail-sampler sizing. With pinned
Alloy `v1.19.2`, upstream defaults are a 30-second decision wait and 50,000 trace
slots **per alias sampler**; decision caches are inactive by default. These are
inherited defaults, not measured or approved bounds. A late span may miss a
decision, and in-memory pending traces can be lost on restart or overload. No
late-span or cache-behavior test has been run.

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
configuration does not select these as an approved capacity budget. With the
pinned `vmauth:v1.153.0` image and no explicit overrides, its source defaults are
100 concurrent requests per user, 1,000 globally, and a 10-second queue duration.
Those defaults are likewise not a measured or approved public-ingestion policy.
Do not infer a safe request ceiling, no-loss behavior, or availability promise
from these defaults. Keep the public route closed until measured traffic informs
explicit limits and the behavior is tested.

VictoriaLogs and VictoriaTraces are staged for `6M` native fixed-duration
retention; VictoriaMetrics remains at 365 days. The staged application cleanup
caps are 8 GiB for logs and 20 GiB for traces. Existing PVC requests remain
unchanged: 8 GiB for logs and 10 GiB for traces, both using `local-path`. This
StorageClass uses shared node storage rather than enforcing a per-claim filesystem
quota. Disk pressure can cause earlier deletion than the age setting; `6M` is not
a minimum-history guarantee. Trace disk usage was not measurable when the cap was
selected, so 20 GiB is an allowance, not a stored-size forecast. These retention
and cap settings have not been deployed.

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

Actual encrypted credentials also passed the pinned vmauth dry-run via stdin,
with output suppressed; gateway/route/policy schemas passed Kubernetes server
dry-run. These checks do not deploy resources. Before opening the route, still
verify hostname approval, TLS issuance, live NetworkPolicy isolation and
direct-receiver denial, production-key acceptance and resource/load behavior.
Validate real client SDK temporality and late-span and
queue-loss behavior explicitly; no test of those defaults is claimed. Review
Flux reconciliation and effective retention after an approved rollout. Do not
enable `public-otlp-ingress.yaml` until these gates are complete.

Pinned references: [Alloy tail sampling](https://grafana.com/docs/alloy/v1.19/reference/components/otelcol/otelcol.processor.tail_sampling/),
[Alloy OTLP/HTTP exporter](https://grafana.com/docs/alloy/v1.19/reference/components/otelcol/otelcol.exporter.otlphttp/),
and [vmauth v1.153.0 source defaults](https://github.com/VictoriaMetrics/VictoriaMetrics/blob/v1.153.0/app/vmauth/main.go).