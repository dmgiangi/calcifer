# Authorization server operations

The Cloud and Home overlays each include a SOPS-encrypted Secret named
`authorization-server-secrets`. The encrypted copies use the same identity
material so either instance can validate tokens from the other. The Secret
contains these keys:

- `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, to be supplied from a Google
  OAuth client whose authorized redirect URIs include:
  - `https://auth.calcifer.tech/login/oauth2/code/google` (the only supported callback)
- `GRAFANA_OIDC_CLIENT_SECRET` and `GRAFANA_API_CLIENT_SECRET`, generated as
  distinct random values.
- `AUTH_LOCAL_LOGIN_PASSWORD_HASH`, an Argon2id or bcrypt hash of the
  operator-chosen fallback password.
- `jwt-private-key.pem`, a PKCS#8 RSA private key generated locally.

Never place any plaintext value in Git or paste it into a terminal transcript.
Generate and edit the Secret in an editor running SOPS. Cloud and Home use the
same issuer, canonical user ID, client IDs, signing key, and password hash;
keep the encrypted copies synchronized when rotating them. Do not introduce a
cluster name into an application claim.

Until both cluster Secrets contain the password hash, keep
`AUTH_LOCAL_LOGIN_ENABLED=false` in the active overlay. Enabling the flag
without the hash intentionally fails startup rather than silently exposing an
unconfigured login path. Both repository overlays currently set the flag to
`true` because both encrypted Secrets contain the hash. To generate a hash
without putting the password in shell history, use an interactive
bcrypt-capable tool such as:

```sh
htpasswd -nB dem.gianluigi
```

Copy only the resulting hash into the SOPS-encrypted Secret. Never commit the
command output or the plaintext password.

Alternatively, update both existing encrypted Secrets with the repository
helper. It reads one password line from stdin, or hides the password when stdin
is a terminal:

```sh
python3 scripts/set-authorization-server-password.py
```

The helper requires `sops`, `htpasswd`, and access to the configured age key. It
verifies that both input files are encrypted, stages both updates, and prints
only a success message. It does not enable the login feature; after reviewing
the changes, set `AUTH_LOCAL_LOGIN_ENABLED=true` in both overlays.

`auth.calcifer.tech` remains the canonical issuer and compatibility path:
public DNS sends it to Cloud and Home LAN DNS sends it to Home. Stateful
browser flows use this same canonical hostname. Redis in Cloud is authoritative
while connected; Home uses a fresh process-local epoch during a private-transit
outage and never merges isolated state back into Redis. The retired
`auth-cloud.calcifer.tech` and `auth-home.calcifer.tech` names are not supported
OAuth endpoints.

Both overlays enable resilient state with the same `auth` Redis namespace and a
five-minute access-token lifetime. Cloud fails closed when Redis is unavailable;
Home keeps local password login available after failure hysteresis and recovers
automatically after stable connectivity returns. Provision the dedicated
`authorization/redis-auth` Secret with the expected
`AUTH_STATE_REDIS_USERNAME` and `AUTH_STATE_REDIS_PASSWORD` keys. The Cloud and
Home overlays include SOPS-encrypted manifests for this Secret; they contain no
plaintext credentials and require the cluster SOPS age key during reconciliation.

The Home Flux Kustomization carries the same local password fallback
configuration as Cloud. The live outage procedure remains an acceptance check
before considering the change complete.

The only release path is the manually started **Release authorization server**
workflow. It compiles the native executable, publishes a linux/amd64 GHCR
image, then commits the same digest-pinned image reference to both Cloud and
Home. Revert that promotion commit to roll back the binary in both overlays.
Do not use a mutable image tag.

Safe operational checks and recovery:

- Render both overlays with `kubectl kustomize` and inspect only non-secret
  configuration. Use `sops filestatus` to confirm that both identity Secrets
  remain encrypted; never decrypt them into a terminal or CI log.
- Check `Certificate` readiness and expiry in each cluster. Home renewal needs
  its configured Azure DNS access while connectivity is available; an already
  issued certificate continues serving during a temporary Internet outage.
- Rotate the common signing key and client credentials by editing both Secrets
  with SOPS, releasing both overlays together, and validating both JWKS paths
  before retiring old material. Treat a suspected key compromise as an
  immediate coordinated rotation.
- Recover the password fallback with `htpasswd -nB dem.gianluigi` in an
  interactive terminal, copy only the resulting hash into both SOPS Secrets,
  then set `AUTH_LOCAL_LOGIN_ENABLED=true` in both overlays and verify the LAN
  outage path before enabling the public path.
- Roll back a bad image or configuration with a Git revert, then reconcile both
  Flux Kustomizations. Keep the two overlays on the same release digest.
