# Authorization server operations

The cloud overlay includes a SOPS-encrypted Secret named
`authorization-server-secrets`. Its Google values remain intentionally empty
until the Google OAuth client is created; it contains these keys:

- `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, to be supplied from a Google
  OAuth client whose redirect URI is `https://auth.calcifer.tech/login/oauth2/code/google`.
- `GRAFANA_OIDC_CLIENT_SECRET` and `GRAFANA_API_CLIENT_SECRET`, generated as
  distinct random values.
- `jwt-private-key.pem`, a PKCS#8 RSA private key generated locally.

Never place any plaintext value in Git or paste it into a terminal transcript.
Generate and edit the Secret in an editor running SOPS. The home copy additionally
needs `AUTH_LOCAL_LOGIN_PASSWORD_HASH`, generated locally from an operator-chosen
password using Argon2id or bcrypt; home must also have a distinct issuer, DNS,
TLS certificate, Google OAuth client and signing key. Do not share cloud/home
keys or client secrets.

`calcifer-home` is a renderable overlay only. Add it to a home Flux root only
after its split-horizon FQDN and TLS issuer have been decided.

The only release path is the manually started **Release authorization server**
workflow. It compiles the native executable, publishes a linux/amd64 GHCR
image, then commits a digest-pinned cloud image reference. Revert that promotion
commit to roll back the binary. Do not use a mutable image tag.
