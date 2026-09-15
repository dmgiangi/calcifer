# Sanitized verification record

## Run metadata

- Timestamp: `2026-09-15T14:33:42Z`
- Git/Flux revision: `master@sha1:24801379e936`
- Promotion: authorization server `0.1.23`
- Workflow run: `34981431328`, successful
- Image: identical digest-pinned artifact on Cloud and Home
- Contexts: `calcifer-cloud`, `calcifer-home`

## Passed checks

- Flux sources and all Kustomizations are ready on both clusters at the recorded revision.
- Both authorization-server Deployments are rolled out and available `1/1`; the Cloud Redis StatefulSet is ready `1/1` with its PVC bound.
- Redis is intentionally hosted only on Cloud; Home has no local Redis StatefulSet and uses the private Redis endpoint through WireGuard.
- Both certificates are ready, both edges return HTTPS `200`, and discovery advertises only `https://auth.calcifer.tech`.
- JWKS, liveness, readiness, Prometheus state metrics, structured logs, and sanitized diagnostic checks passed.
- Cloud and Home use different DNS answers for the canonical hostname; both initiate Google login with only the canonical callback.
- Retired hostnames are absent from active application/Ingress configuration and do not provide usable HTTPS OAuth endpoints.
- Redis persistence, ACL, `noeviction`, AOF/PVC, private reachability, public/undeclared-port denial, WireGuard routing, and firewall checks passed.
- Grafana and both authorization/Redis alert groups report ready/synchronized status.
- The selected authorization-state test suites passed: 42 tests, 0 failures, 0 errors, 0 skipped.

## Controlled outage result

- Baseline mode was `CONNECTED` at generation `9` on both clusters.
- Home crossed hysteresis and entered `ISOLATED`; local login returned `200`, Google returned `503`, and readiness remained `200`.
- Cloud remained `CONNECTED`; readiness remained `200` and Google initiation returned `302`.
- After restoring only the blocked private Redis flow, Home entered `RECOVERING`; stateful requests temporarily returned `503`.
- Home returned to `CONNECTED` at generation `10`; the generation delta was exactly one and remained stable.
- The temporary firewall rules were removed, WireGuard handshake was recent, and both clusters reported Redis reachable at generation `10`.

## Pending interactive evidence

- Google Console cleanup confirmed by the user: the two retired redirect URIs were removed and the canonical callback was retained.
- Complete password and Google authentication, authorization-code/PKCE exchange, Grafana login, client credentials, and resource-server JWT validation from the required network locations.
- Capture authenticated connected-session restart persistence and pre-recovery session/code rejection without recording identifiers or credentials.
- Validate a pre-recovery JWT until expiry and then complete tasks 9.3–9.6, 10.6, 10.12, and 10.13.

## Additional autonomous verification

- Timestamp: `2026-09-15T13:25:16Z`
- Maven test suite: `42` tests, `0` failures, `0` errors, `0` skipped.
- `kubectl kustomize` rendered both overlays successfully: Cloud `42` resources, Home `41` resources.
- Both Flux clusters reported all listed Kustomizations `Ready=True` at `master@sha1:78af1662`.
- Direct Kubernetes API-proxy checks reported `UP`, generation `10`, `CONNECTED`, and Redis reachable on both authorization-server Pods.
- Canonical endpoint checks returned `/login=200`, Google initiation `302`, and callback without a code `503`.
- Retired endpoint hosts returned `404` for both Google initiation and callback paths.
- Grafana reported `GrafanaReady=True`; both authorization alert groups reported `AlertGroupSynchronized=True` with `ApplySuccessful`.

## Native session deserialization incident

- At `2026-09-15T14:18:24Z`, an authenticated Grafana authorization request through Home returned application-level `503`.
- Sanitized logs identified `ClassNotFoundException: java.time.Ser` while deserializing the Redis-backed browser session; Redis and WireGuard were healthy.
- The native serialization hints now explicitly register the package-private Java time serialization proxy, with a regression assertion and generated AOT metadata verification.
- Release `0.1.23` passed all 42 JVM tests and the CI native compilation, then rolled out the same immutable digest to Cloud and Home.
- Both Deployments reached `1/1`, readiness returned `UP`, generation remained `10`, Redis reachability remained `1`, and an anonymous Grafana authorization request returned `302` to the canonical login page on both edges.
- Final authenticated Grafana browser confirmation remains pending; no session identifier, PKCE value, authorization code, token, or credential is recorded here.