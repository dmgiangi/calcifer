# Homepage and declarative OIDC operations

Homepage is deployed from `clusters/apps/homepage/web-base` and the
`cloud-web`/`home-web` overlays. Both instances use `https://calcifer.tech`,
while Home publishes a LAN-only `DNSEndpoint` for split-horizon DNS.

## Add or update a dashboard service

1. Edit `clusters/apps/homepage/web-base/services.yaml` and add the canonical HTTPS
   link and a `siteMonitor` URL that represents end-to-end reachability.
2. Render both overlays and confirm that the entry is present:

   ```sh
    kubectl kustomize clusters/apps/homepage/overlays/cloud-web >/tmp/homepage-cloud.yaml
    kubectl kustomize clusters/apps/homepage/overlays/home-web >/tmp/homepage-home.yaml
   ```

3. Commit and let Flux reconcile `cloud-apps` and `home-apps`.
   The generated ConfigMap name includes a content hash, so a catalog change
   rolls the Homepage Deployment automatically.

Do not enable Kubernetes discovery or add RBAC. A service that is unavailable
from one cluster should remain in the catalog and appear unavailable there.

## Add a declarative OIDC client

1. Add non-secret metadata under `identity.clients` in
   `clusters/apps/authorization-server/base/clients.yaml`. Use a unique client
   ID, exact redirect URIs, least-privilege scopes and grant types, and PKCE for
   interactive clients.
2. Reference the client secret as an environment placeholder, for example
   `${MY_APP_OIDC_CLIENT_SECRET}`. Add that key to both overlay
   `secret.example.yaml` files.
3. Add the same generated secret to both encrypted
   `authorization-server-secrets.sops.yaml` files using the repository's SOPS
   workflow. Never put the value in command arguments, logs, or plaintext Git.
4. Add the matching secret to the application's encrypted Secret. Verify
   equality using hashes only; never print decrypted values.
5. Increment `calcifer.tech/authorization-secrets-revision` in
   `clusters/apps/authorization-server/base/deployment.yaml` whenever a Secret
   value changes. ConfigMap changes roll automatically through the generated
   name; the explicit revision bump provides the controlled rollout for Secret
   changes.
6. Render both Authorization Server overlays and run its unit and native build
   checks before committing.

A missing secret placeholder or invalid client definition intentionally fails
Authorization Server startup validation.

## Post-rollout verification

Check rollout state without reading Secret values:

```sh
flux --context calcifer-cloud get kustomization authorization-server cloud-apps
flux --context calcifer-home get kustomization authorization-server home-apps
kubectl --context calcifer-cloud -n web rollout status deployment/homepage
kubectl --context calcifer-home -n web rollout status deployment/homepage
```

From a public client, confirm `calcifer.tech` resolves to Cloud. From a Home LAN
client, confirm it resolves to `192.168.0.102`. In both environments, open
`https://calcifer.tech` in a fresh browser session, verify redirect to
`https://auth.calcifer.tech`, complete login, and confirm return to the Homepage
callback and an authenticated catalog.

Finally, verify Authorization Server and Grafana show the expected
`siteMonitor` state. During a controlled Home Internet outage, the dashboard
must remain reachable through LAN DNS; a Cloud-only service may correctly show
as unavailable.
