## ADDED Requirements

### Requirement: Authenticated public OTLP HTTP ingestion
The system SHALL provide an HTTPS OTLP/HTTP ingestion endpoint on `calcifer-cloud`
for POST requests to `/v1/traces`, `/v1/logs`, and `/v1/metrics`. The proposed
hostname is `otlp.calcifer.tech`, approved for public rollout on 2026-10-09. All three
signals SHALL require a valid bearer API key before reaching the collector.

#### Scenario: Authorized signal ingestion
- **WHEN** either authorized client submits a supported OTLP payload to its signal path with a valid key
- **THEN** the request SHALL enter the matching collector pipeline and be forwarded to the internal Victoria backend subject to documented trace sampling and resource limits

#### Scenario: Missing, invalid, or revoked credential
- **WHEN** a request does not contain a currently authorized bearer credential
- **THEN** the authentication gateway SHALL reject it without forwarding telemetry to Alloy

#### Scenario: Non-ingestion public access
- **WHEN** a public request targets a collector UI, health/admin/metrics endpoint, backend query path, or unsupported method/path
- **THEN** it SHALL not reach those endpoints through the public ingestion route

### Requirement: Independent client credentials and trusted attribution
The system SHALL use independent credentials named `peer-reviewer-local` and
`peer-reviewer-github`. Client identity SHALL be derived from successful
authentication and recorded as `calcifer.ingest.client`, not accepted from
client-provided headers or attributes. Either key SHALL be independently revocable
without changing the other key or existing stored identity.

#### Scenario: Client attempts identity spoofing
- **WHEN** the local client submits telemetry claiming the GitHub client's identity
- **THEN** stored attribution SHALL identify `peer-reviewer-local` and conflicting client-provided identity SHALL not override it

#### Scenario: One key is revoked
- **WHEN** the GitHub key is removed from the active authentication configuration
- **THEN** it SHALL fail authentication after the documented reload/restart and the local key SHALL continue working

### Requirement: Secret generation and delivery without exposure
Each credential SHALL contain at least 256 bits of cryptographic randomness and
SHALL be generated without exposing plaintext through tool output, arguments,
logs, shell history, or tracing. Server-side credentials SHALL be stored in a
SOPS-encrypted Kubernetes Secret and never committed in plaintext.

#### Scenario: Local credential delivery
- **WHEN** the local credential is provisioned
- **THEN** `/home/dmgiangi/.bashrc` SHALL export `CALCIFER_OTLP_API_KEY` with only the local key, preserve unrelated content, use owner-only permissions, and not be displayed during provisioning

#### Scenario: GitHub credential delivery
- **WHEN** the GitHub credential is provisioned
- **THEN** `gh` SHALL set repository Actions secret `CALCIFER_OTLP_API_KEY` in `dmgiangi/peer-reviewer` with only the GitHub key through stdin, and verification SHALL reveal metadata or pass/fail only

#### Scenario: Existing credential destination
- **WHEN** the named local export or repository secret already exists
- **THEN** provisioning SHALL request approval before overwriting or rotating it without retrieving or displaying its value

### Requirement: Boolean force-keep trace sampling exemption
Within an authenticated client pipeline, a trace with the OTLP resource or span
attribute `calcifer.sampling.keep` set to boolean `true` observed before its
sampling decision SHALL be retained by the trace sampler. The policy SHALL retain
the received trace as a unit, not only the marked span. No other sampling rule
SHALL override this exemption. The exemption SHALL NOT bypass authentication,
request/resource limits, or storage retention.

#### Scenario: A timely span or resource requests exemption
- **WHEN** at least one received span or resource carries boolean `true` before the trace sampling decision under normal capacity
- **THEN** every received span covered by that trace decision SHALL be forwarded regardless of the default 50% sampling result

#### Scenario: No boolean exemption is present
- **WHEN** the attribute is missing, false, a string, or another non-boolean value
- **THEN** the trace SHALL follow the normal 50% trace-sampling policy

#### Scenario: Client discarded data or marker arrives after dropping
- **WHEN** an SDK has already discarded spans or a force-keep marker arrives after the collector dropped a trace
- **THEN** the system SHALL not claim to recover those spans and the client documentation SHALL explain the timing and export constraints

### Requirement: Fifty-percent default sampling without cross-client mixing
The system SHALL retain approximately 50% of external traces lacking the boolean
exemption using a trace-level probabilistic decision. Spans for the same
authenticated client and trace ID SHALL reach the same sampler instance.
Different client credentials SHALL not share a sampling decision solely because
they submit identical trace IDs. Logs and metrics SHALL bypass trace sampling.

#### Scenario: Unmarked trace population
- **WHEN** a representative set of independently generated unmarked trace IDs is submitted under normal capacity
- **THEN** the configured sampling percentage SHALL be 50 and observed retention SHALL be statistically consistent with that rate, without independently sampling spans within one trace

#### Scenario: Identical IDs from different clients
- **WHEN** one client marks its trace to keep and another submits the same trace ID without a marker
- **THEN** the second client's decision SHALL remain independent and SHALL follow its own sampling policy

#### Scenario: Logs or metrics are submitted
- **WHEN** either authenticated client submits logs or metrics with or without the sampling attribute
- **THEN** all accepted records SHALL bypass trace-sampling decisions and enter the corresponding backend exporter

### Requirement: Bounded ingestion with private existing collection preserved
Collector memory SHALL use the approved explicit limits. Custom public request
rates/sizes, sampler buffers/caches and exporter queue bounds SHALL remain deferred
until measured and approved; the authorized initial public rollout SHALL use the
documented pinned-version defaults without claiming validated load capacity or
lossless ingestion. Credentials and request bodies SHALL
not appear in access logs or diagnostic telemetry. The existing Cloud Alloy
DaemonSet, private Home ingestion, and internal backend query paths SHALL remain
available without changing `calcifer-home` manifests or requiring public keys.

#### Scenario: Capacity is exhausted
- **WHEN** a client exceeds a configured request, memory, or queue limit
- **THEN** rejection or loss SHALL be observable without credential/payload disclosure and force-keep SHALL not disable that protection

#### Scenario: Existing infrastructure continues sending telemetry
- **WHEN** existing Cloud workloads or the Home collector send telemetry through their current private paths
- **THEN** those paths SHALL continue to work without using the new credentials or entering the external 50% trace-sampling policy
