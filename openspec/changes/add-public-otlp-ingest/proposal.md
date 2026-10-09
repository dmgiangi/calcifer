## Why

External peer-reviewer clients need an authenticated Internet endpoint on
`calcifer-cloud` for OpenTelemetry traces, logs, and metrics. The current Alloy
receiver is private and forwards only traces. Separate credentials must support
local development and GitHub Actions without exposing their values, while clients
need an explicit way to exempt important traces from sampling.

## What Changes

- Propose `https://otlp.calcifer.tech` for OTLP/HTTP ingestion only, behind Traefik
  TLS and a multi-key authentication gateway.
- Add independent bearer credentials named `peer-reviewer-local` and
  `peer-reviewer-github`, stored server-side in a SOPS-encrypted Kubernetes Secret.
- Provision `CALCIFER_OTLP_API_KEY` in `/home/dmgiangi/.bashrc` for the local key
  and as a GitHub Actions repository secret in `dmgiangi/peer-reviewer` for the
  GitHub key, using `gh` with secret input through stdin.
- Introduce a dedicated single-replica Alloy ingestion gateway, preserving the
  existing Alloy DaemonSet and private ingestion paths.
- Honor the boolean OTLP attribute `calcifer.sampling.keep=true` by keeping the
  trace; retain approximately 50% of other external traces. Logs and metrics are
  not sampled by this policy.
- Increase the existing VictoriaLogs and VictoriaTraces time-retention settings
  to six backend-native months (`6M`), including infrastructure data. Keep the
  existing 365-day metrics retention and shared backends.
- Retain approved collector memory limits. Defer custom request size/rate,
  sampling buffers and exporter queues until measurements and explicit approval;
  the user directed public rollout with the documented pinned defaults on
  2026-10-09. Monitor losses and storage pressure without logging credentials or
  payloads; this is best-effort ingestion, not validated load capacity.

## Capabilities

### New Capabilities

- `observability-public-otlp-ingest`: Public TLS ingestion, independent bearer
  credentials, server-derived client identity, safe credential delivery, and
  force-keep-aware trace sampling.
- `cloud-trace-observability`: Six-month time retention for the shared existing
  VictoriaTraces backend without breaking internal trace ingestion.

### Modified Capabilities

- `cloud-log-observability`: Increase durable log time retention from 14 days to
  six months while documenting disk-pressure eviction separately.

## Impact

- Runtime changes will be confined to `clusters/calcifer-cloud/apps/observability/`
  and the corresponding specifications. `calcifer-home` manifests remain unchanged.
- `vmauth` is proposed as a new authentication workload; adding its pinned chart
  or image requires approval before implementation. Existing Alloy documents one
  bearer token per authenticator, not a multi-key credential registry.
- Provisioning will modify one local shell startup file and one named secret in
  the explicitly requested GitHub repository. Neither credential has been
  generated or installed during proposal creation.
- Longer retention plus external ingestion increases storage demand. The 8 GiB
  log and 20 GiB trace disk-cleanup caps, and existing 8/10 GiB PVC requests, do
  not guarantee six months of actual history; disk-pressure cleanup can evict
  older data before its age-based retention expires.
- Strict OpenSpec validation passes. The private gateway, retention and disk
  bounds have been reconciled by Flux. The user's instruction to proceed after
  the public-opening clarification authorizes `otlp.calcifer.tech` with custom
  request/sampling bounds deferred. Public TLS/authentication acceptance is in
  progress; load and real SDK acceptance remain separate. See `preflight.md`.
