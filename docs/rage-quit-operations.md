# Rage Quit operator setup

## Scope and authorization

Rage Quit runs only in `calcifer-cloud`, namespace `web`, at
`https://rage-quit.calcifer.tech`. `cloud-apps` remains the sole Namespace owner.
The dedicated Flux entry depends on `authorization-server`,
`cert-manager-config` and `cloud-apps`; unrelated app dependencies are unchanged.
It starts with `spec.suspend: true` and must remain suspended until all gates below
pass. Merely merging the manifests is not approval to activate the service.

Obtain separate authorization for auth rollout, GHCR release dispatch/promotion,
Cloud activation, DNS changes, secret provisioning and any production restore.
No app ServiceAccount, RBAC or API token is supplied. Maintenance uses an
operator identity with the narrowly necessary Flux/workload/PVC permissions.
Never use production credentials or personal records in local verification.

## Required prerequisites

1. The operator and all participants agree one `Europe/Rome` ISO start date.
   Add a Cloud ConfigMap patch setting `RAGE_QUIT_START_DATE`, and reference that
   patch from the overlay. There is intentionally no default. On an existing
   volume it must match `settings.start_date`; do not edit the DB to shift history.
2. DNS for `rage-quit.calcifer.tech` must resolve to the existing Cloud edge.
   Confirm the existing `letsencrypt-production-azure` ClusterIssuer is ready,
   its DNS challenge authority covers this hostname, and the `web` certificate
   becomes valid. Cloud Traefik already redirects HTTP to HTTPS globally.
   Do not route the hostname to Home or create a second web Namespace.
3. Both auth edges must run a compatible, tested symmetric auth release with the
   three-participant policy. Existing applications remain administrator-only;
   unknown subjects and non-Google sessions cannot authorize Rage Quit. The
   administrator's password fallback still works for existing clients only.
4. Both edges must declare client/audience `rage-quit`, only authorization-code,
   required PKCE, `client_secret_basic`, scopes `openid profile email`, and only
   callback `https://rage-quit.calcifer.tech/login/oauth2/code/rage-quit`.
   Supply `AUTH_CLIENT_RAGE_QUIT_SECRET` through each edge's existing encrypted
   Secret/configuration mechanism. Enable the auth `rage-quit` profile on both
   edges only after the complete participant catalog and secret exist. Follow
   the authorization-server owner's provisioning instructions; this app change
   does not modify either auth overlay or release workflow.
5. Provision the **same** client secret as `RAGE_QUIT_CLIENT_SECRET` in
   `web/rage-quit-secrets` on Cloud through SOPS. No direct Google credential,
   signing key or Kubernetes API token belongs in the app Secret.
6. Publish a new tested image with the manual Rage Quit workflow and review its
   Cloud-only digest promotion. `operator-must-release` is not a usable release.
   The app package must be public in GHCR (or explicitly configure an encrypted
   image pull credential before activation; none is invented here).
7. Confirm `local-path` supports a 1Gi RWO claim on the intended Cloud node,
   ownership UID/GID 10001, and an off-node encrypted backup destination exists.
   RWO alone is not a one-writer lock; the Deployment must stay at one replica
   with `Recreate`, never run a second writer or rolling surge.

## Secret provisioning without plaintext in Git

`clusters/apps/rage-quit/overlays/cloud/secret-template.yaml` is a documentation
fixture, not an overlay resource. Its only value is the explicit non-credential
placeholder `REPLACE_WITH_SECRET`; application startup rejects that value. Do not
deploy it and do not invent SOPS ciphertext. The pod references the mandatory key
with `optional: false`; a missing Secret/key, empty value, or placeholder fails
closed.

An authorized operator creates a fresh high-entropy secret into a restricted
0600 file outside Git using approved tooling (never command-line values, shell
history, debug tracing or terminal output). Store the auth-edge input and Cloud
app input via their protected file-based provisioning paths. Keep private SOPS
identities outside the repository; the Flux `flux-system/sops-age` Secret must
already exist. The repository's SOPS rule must select the correct Cloud recipient.

The following is an **operator-only template**, not a command executed by this
implementation. Substitute protected paths, not secret values. The input file
must contain the exact secret with no accidental newline; do not display it.

<augment_code_snippet mode="EXCERPT">
````sh
kubectl create secret generic rage-quit-secrets --namespace web \
  --from-file=RAGE_QUIT_CLIENT_SECRET=/private/rage-quit-client-secret \
  --dry-run=client -o yaml | sops --encrypt \
  --filename-override clusters/apps/rage-quit/overlays/cloud/secrets.sops.yaml \
  /dev/stdin > clusters/apps/rage-quit/overlays/cloud/secrets.sops.yaml
````
</augment_code_snippet>

Use `set -o pipefail`, restrictive umask and no `set -x`. Only after encryption
succeeds, add `secrets.sops.yaml` to the Cloud overlay's resources. Verify only
metadata/key **names**: Secret `rage-quit-secrets`, namespace `web`, key
`RAGE_QUIT_CLIENT_SECRET`, a real SOPS envelope and expected recipient metadata.
The two auth Secrets must instead expose their owner's
`AUTH_CLIENT_RAGE_QUIT_SECRET` key. Never print/render decrypted Secret data or
commit a plaintext intermediate. A metadata-only check cannot prove secret
equality: establish equality through the protected provisioning source and
successful operator OIDC acceptance on both edges.

## Network and edge behavior

Ingress admits only K3s Traefik pods (`kube-system`,
`app.kubernetes.io/name=traefik`) on 8080. Kubelet probes are node-origin traffic;
confirm the actual CNI and node behavior during live acceptance. Actuator paths
are excluded from the public HTTPS route, not published as monitoring endpoints.
Egress admits CoreDNS (UDP/TCP 53), public IPv4 HTTPS and Cloud Traefik HTTPS
(443/8443) for public issuer hairpin routing. It does **not** call auth's internal
HTTP service or require widening the existing authorization ingress policy.

Standard K3s NetworkPolicy cannot allow a DNS hostname precisely; public TCP 443
is the deliberate OIDC egress trade-off, excluding private/link-local ranges.
Validate the issuer's Cloud/Home DNS edges, service DNAT/hairpin and Traefik
labels with the operator before activation. IPv6-only or private issuer DNS
answers require an explicitly reviewed policy adjustment, not a blanket egress
allow. No Kubernetes API, data database service or monitoring egress is needed.

## Release and rollout order

The manual `release-rage-quit.yaml` accepts strict SemVer without build metadata
(Docker tags cannot contain `+`), rejects leading-zero numeric components and
unsafe syntax, and requires dispatch on `master`. Tests/package, shell syntax,
version rejection fixtures, Java YAML/workflow/Flux checks, suppressed Kustomize
builds and hardened offline container checks all precede the publishing job.
The verified Docker image is passed as a short-retention artifact, not rebuilt
after validation. Publishing checks that the version/tag is unused, fails closed
on registry errors, publishes a versioned GHCR image, validates the digest, and
commits only the Cloud image patch. Branch/tag push is atomic; a concurrent branch
change requires human review, never a forced push or automatic rebase.

Restrict package write permissions to this release path: GHCR does not provide
server-enforced immutable tags against other publishers. Workflow concurrency
and version checks protect this workflow, while deployments always use the digest.
If push succeeds but promotion fails, retain the artifact and manually review
the unpromoted version; do not overwrite/reuse the version or dispatch it again.
Ensure the GitHub token has package/contents write and branch policy permits the
reviewed promotion; the verify job has read-only permissions.

Roll out **auth first on both edges**, test existing administrator/machine flows
and participant denials, then verify TLS/DNS/secret/start-date/image prerequisites.
Only after explicit approval remove the Cloud Flux suspension declaratively.
Watch startup/readiness without dumping pod env, Secret YAML, tokens or request
logs. Test the acceptance checklist; encrypted off-node backup plus disposable
restore is required before operational readiness. Home has no Rage Quit app.

If auth access regresses, pause Rage Quit and coordinate a **symmetric** auth
rollback through its owner. Never roll back one auth edge independently. An
app-only rollback changes only its compatible Cloud digest and keeps its PVC.
