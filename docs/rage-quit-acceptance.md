# Rage Quit acceptance and verification record

## Local evidence versus live acceptance

Local validation is not permission for deployment, DNS/secret writes, release
dispatch or restore. Only verified tasks are checked in the OpenSpec change.
The following local checks are intended to be recorded by the implementation
agent/operator after running them; no unrun test is implied by this checklist.

### Local implementation evidence before the authorized rollout

- Application Maven `verify` passed: 68 tests, zero failures/errors/skips; executable jar packaged.
- Authorization-server Maven `test` and `verify` passed: 137 tests, zero failures/errors/skips.
- Node UI fixtures passed: 9 tests, including idempotent retry, CSRF requests,
  Rome offsets, corrections, missing/zero data, tied ranks and accessible charts.
- The application HTTP OIDC test uses a local synthetic issuer and verifies all
  three identities; authorization-server code/token/UserInfo and policy tests run
  separately. No real Google login or single two-process deployment test ran.
- UI fixture tests cover SVG/ARIA labels, accompanying tables and responsive CSS
  breakpoints, but no browser-based mobile/desktop visual acceptance was run.
- Local linux/amd64 JVM image build and hardened runtime smoke check passed:
  non-root/read-only root, bounded executable tmpfs for SQLite extraction,
  durable storage across restart, issuer-outage probes and missing-date rejection.
- All five Kustomize builds passed with output suppressed: app Cloud overlay,
  Cloud/Home roots and Cloud/Home authorization overlays (read-only).
- One client secret was generated and stored only as SOPS-encrypted data in the
  Cloud app Secret and both Cloud/Home authorization-server Secrets. Local SOPS
  decryption checks and metadata-only namespace/key/encryption validation passed;
  plaintext was not printed. No Secret manifest was applied to a cluster.
- Java manifest/workflow structural checks and Bash syntax/version fixtures passed.
- Synthetic encrypted offline backup and guarded restore passed, including
  retained current state, exact persisted-input comparison, owner/mode checks,
  invalid-snapshot rejection and changed-start-date rejection without overwrite.
- IDE build passed; Maven provides standalone module compilation. Native-image
  execution and real Redis/edge/Google acceptance are not claimed by JVM fixtures.
- Strict OpenSpec validation passed after retaining scenarios introduced by the
  previous archived change. Full actionlint and shellcheck were not run: those
  CLIs are not installed. Bundled structural checks are not full workflow lint.
- No live cluster operation, participant-data access, release dispatch, registry
  publication, Git commit/push, DNS change, cluster-side Secret write or
  production restore ran. The local encrypted-manifest writes are recorded above.

### Canonical subjects and client entitlements

The shared identity catalog's existing mappings are preserved: Dem uses
`user:admin`, Pugliens uses `user:moody`, and Frevadiscor uses
`user:frevadiscor`. The application registry, opt-in auth client profile,
database constraints, and synthetic fixtures use the same stable subjects; no
identity migration is needed. A subject identifies a person, while each
application's client policy independently determines access. The Rage Quit
profile explicitly permits these subjects for its client; existing browser
clients remain administrator-only by default. Granting a person access to a
future client requires a policy change for that client, not a subject rename.

At the local handoff, the profile remained opt-in. The same client secret was provisioned in the
local SOPS-encrypted Cloud app and Cloud/Home auth Secret manifests, but these
manifests and the shared client profile had not been applied or activated in
either cluster. Real participant login and Cloud rollout were pending the
documented auth-first configuration, operator prerequisites, and explicit
authorization.

### Authorized testing rollout — 2026-10-05

The operator explicitly requested deployment for testing and selected the most
recent midnight in `Europe/Rome`: `2026-10-05T00:00:00+02:00`. The Cloud overlay
sets `RAGE_QUIT_START_DATE=2026-10-05`; both auth overlays enable the `rage-quit`
profile, with explicit `rage-quit-user` roles for the two non-admin participants.

- Authorization server `0.2.0` release passed, including native compilation,
  native tests, the JVM suite and both overlay builds. Both clusters rolled out
  Ready `1/1` with the same digest
  `sha256:c8717b276e6c5f69f8c1977f8e1097081c64b32a90d5314f7806c69cce3ba608`.
- Synthetic native startup with the client profile and local administrator
  fallback enabled passed liveness and OIDC discovery checks. Local auth tests
  passed (137 total plus 11 focused post-configuration regressions); app tests
  passed (68), as did all 9 Node UI fixtures and the IDE/structural checks.
- Both live auth edges returned HTTP 200 for liveness, readiness and discovery.
  Anonymous Rage Quit authorization correctly starts Google authentication;
  both edges generate the expected Google callback. This is not a completed
  real-user login or an authenticated existing-client acceptance test.
- Rage Quit `0.1.0` release passed all verification/publication gates. The first
  attempt stopped before publishing on ShellCheck diagnostics; helper source
  lookup and unused variables were corrected, and the subsequent CI run passed.
  Anonymous registry pull access was verified. Cloud uses digest
  `sha256:1cc803debce028e48504268ba03880239ffa05c8dadaca05c55a9fe890bf43c0`.
- Cloud activation is committed in `ebf918e`. Flux reconciliation and Deployment
  rollout passed; the single app replica is Ready, `rage-quit-data` is Bound and
  certificate `rage-quit` is Ready. Home has no Rage Quit workload.
- Public `/login.html` returned HTTPS 200, anonymous `/api/insights` returned 401,
  public `/actuator/health` returned 404, and HTTP redirects to HTTPS. HSTS, CSP,
  frame and content-type protections are present. Internal app liveness and
  readiness returned 200. OAuth initiation uses the exact callback and S256 PKCE.
- A transient Java HTTP probe inside the app pod reached issuer discovery and
  JWK endpoints over validated HTTPS (both 200), checking actual pod egress.
- No real Google login, user tracking-data read/write, viewport-based visual
  acceptance, live persistence restart, off-node production backup or production
  restore was performed. These remain required for operational sign-off; the
  service is deployed for the operator's requested testing, not fully accepted.

### Central login redirect follow-up — authorized testing rollout — 2026-10-05

- Anonymous navigation to `/` or `/index.html` automatically starts
  `/oauth2/authorization/rage-quit`; the authorization server then redirects
  anonymous Rage Quit authorization requests to its `/login` panel.
- Existing password-backed sessions still require Google authentication for
  Rage Quit. Silent anonymous authorization does not show login or issue a code.
  API requests remain HTTP 401; OAuth state, nonce and S256 PKCE are preserved.
- Source change is committed as `c34625f`. Authorization server `0.2.1` was
  promoted to both Cloud and Home by `ebbcd7b`; both deployments rolled out
  Ready `1/1` with digest
  `sha256:e8aec9a7726cc982bd5c534c4ae9d9efcad11062dd84987611d5ea86f936b2cc`.
- Rage Quit `0.1.1` passed its release workflow and was promoted Cloud-only by
  `2021f413`. The deployment rolled out Ready `1/1` with digest
  `sha256:d75365ffd9e834047f72d7b52e82678b6ed2ca53a38ca78012530570c91a29c7`;
  Flux reports the `rage-quit` Kustomization Ready. The PVC remains Bound and
  the TLS certificate Ready.
- Live anonymous HTTPS navigation to `rage-quit.calcifer.tech/` followed three
  redirects and ended at `https://auth.calcifer.tech/login` with HTTP 200 and
  the Google login panel. Anonymous `/api/insights` returned HTTP 401 without a
  redirect.
- Maven `verify` passed: 68 application tests and 139 authorization-server tests,
  zero failures/errors/skips; both release workflows passed their CI gates,
  including native auth compilation/tests and the app container check. IDE
  build passed with existing Jackson `asText()` deprecation warnings. No real
  Google login or production data/recovery acceptance is claimed.

| Local criterion | Evidence command |
| --- | --- |
| Application tests and executable package | Maven `verify` with JDK 25 |
| Five suppressed Kustomize builds | `bash scripts/rage-quit-validate.sh` |
| Cloud-only graph, one writer, hardening and workflow gates | Bundled Java YAML checker through the validation script |
| Safe and unsafe release versions | Version rejection fixtures in the validation script |
| amd64 read-only non-root image, native extraction and issuer-outage probes | `bash scripts/rage-quit-container-check.sh` |
| Persistent schema/settings across container restart, missing-date rejection | Same container check, disposable volume only |
| Encrypted restore, retained state and invalid/wrong-date rejection | `bash scripts/rage-quit-rehearse.sh` |
| IDE build | IDE build tool, report warnings separately |
| Full workflow/shell lint | Installed actionlint/shellcheck, if available; Java structural checks do not substitute for those tools |
| OpenSpec strict validation | Existing OpenSpec CLI, if available; absence must be reported |

Record actual pass/failure/limitations separately. Synthetic local recovery
compares all persisted inputs and computed-count/ordered-smoking inputs, not a
real browser's charts. The single-writer offline container intentionally has no
issuer access: successful liveness/readiness proves an auth outage alone does
not restart it, but does not prove live DNS/CNI routing or Google login.

## Operator-only live acceptance checklist

1. **Prerequisites:** confirm explicit approvals, Cloud context, authentic image
   digest, agreed start date, exact shared OIDC callback/client/PKCE/scopes,
   genuine encrypted Secret and same client secret on both auth edges. Check
   only Secret metadata/key names, never print values or env. Confirm auth-first
   symmetric rollout, Flux dependencies and sole ownership of `web`.
2. **Edge and isolation:** confirm hostname DNS to Cloud, valid certificate,
   HTTP redirect/refusal, HTTPS headers and no public `/actuator` route. Verify
   actual K3s Traefik labels, node probes, DNS, issuer HTTPS hairpin and both
   auth edges through NetworkPolicy. Confirm Home has no app, route or claim.
3. **Three Google logins:** separately authenticate Dem, Pugliens and Frevadiscor
   using the authorized real identities; verify correct aliases and owner-only
   history. Deny unknown/unverified users and require Google reauthentication
   for the administrator's password-only auth session. Do not record tokens,
   emails, callback codes or screenshots of private histories in public issues.
4. **Existing apps unchanged:** administrator Google/password fallback and
   existing machine flow still work. The two additional participants must be
   denied Grafana, Homepage, Home Assistant, Zigbee2MQTT and any machine client,
   including reused sessions/restored sessions on the opposite auth edge.
5. **Mutation security:** anonymous APIs fail; missing/wrong CSRF and cross-owner
   read/edit/delete requests fail even for the administrator. Verify secure
   session cookies, POST logout, invalidation and no tokens in browser storage.
6. **Reporting semantics:** with explicitly authorized acceptance records,
   verify now/backdated smoke/resistance, correction/deletion, retry deduplication,
   version conflicts, zero confirmation/withdrawal/invalidation and day-in-progress
   labeling. No report differs from confirmed zero; resistance-only is not zero.
   Penalties and resistance points stay separate, ascending cigarette ranks tie,
   empty history is unranked, missing days stay visible, and charts agree with
   corrected counts. Check completed adjacent-cigarette gaps, DST/Rome boundaries
   and unavailable intervals/percentage when history is insufficient.
7. **Persistence and outages:** after a controlled restart and compatible digest
   update, verify the same records/declarations/start date and reports. There
   must never be overlapping writers. All sessions require login again. During
   an approved issuer outage liveness remains healthy; inaccessible storage makes
   readiness fail, never resets or recreates a lost database. Restore normal
   conditions without exposing logs/data.
8. **Off-node recovery:** authorize and stop the writer, create an integrity-checked
   encrypted offline snapshot, transfer to protected off-node retention, verify
   private-key recovery access, and restore onto disposable storage. Compare
   settings/history/declarations/scores/intervals/charts privately with the source
   state. Prove invalid candidates never replace current disposable state and
   pre-restore state remains recoverable. Keep production untouched during rehearsal.
9. **Rollback readiness:** record a real compatible prior Cloud digest, prove a
   compatible rollback retains current records/PVC on disposable storage, and
   review the incompatible-schema recovery guard. Auth rollback remains symmetric.
10. **Operational sign-off:** only after all preceding checks pass, record
    permission-scoped live results and backup retention/recovery owners. Deployment,
    DNS, secrets, release dispatch/push and live restore stay pending if approval
    or any prerequisite is absent. Do not claim OpenSpec apply-complete based on
    manifests alone or this local rehearsal.
