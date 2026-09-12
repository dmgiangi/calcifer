# cert-manager

cert-manager is installed by Flux through the official Jetstack Helm chart. The cluster uses Let's Encrypt with the Azure DNS `DNS-01` solver for `calcifer.tech`.

The installation resources are in [`install/`](install/), while the Azure DNS Secret and `ClusterIssuer` resources are in [`config/`](config/). Flux reconciles these directories separately so the cert-manager CRDs and controller are ready before the configuration is applied.

## Azure DNS credentials

The Azure Service Principal must have the `DNS Zone Contributor` role scoped to the `calcifer.tech` DNS zone. Do not commit the client secret in plain text.

Edit the encrypted Secret locally:

```bash
cd clusters/calcifer-cloud/infrastructure/cert-manager/config
sops azure-dns-credentials.sops.yaml
```

SOPS decrypts the file in your editor and re-encrypts it when you save. The Secret is already included in `config/kustomization.yaml`:

```yaml
resources:
  - azure-dns-credentials.sops.yaml
  - clusterissuers.yaml
```

The Flux `sops-age` Secret must already exist in the `flux-system` namespace. If the Secret has a different name, update the `decryption.secretRef.name` value in [`../../kustomizations/cert-manager-config.yaml`](../../kustomizations/cert-manager-config.yaml).

## Issuers

The `letsencrypt-staging-azure` and `letsencrypt-production-azure` ClusterIssuers are defined with the Azure DNS solver. The test NGINX currently uses the staging issuer.
