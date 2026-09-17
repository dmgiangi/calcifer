# Home Assistant operations

Home Assistant runs only on `calcifer-home`. LAN clients resolve the canonical
name directly to the Home node, while Internet clients enter through
`calcifer-cloud` and traverse the WireGuard private transit.

## DNS prerequisites

Create and retain an Azure DNS `A` record for `home.calcifer.tech` pointing to
`136.144.222.128` outside Flux. This is the public Cloud Traefik edge.

The Home `DNSEndpoint` overrides the same name to `192.168.0.102` for LAN
clients. Do not create an inbound Home router port-forward; public requests
must use Cloud Traefik and `172.31.255.2:443` over WireGuard.

## Workload checks

Confirm the certificate, workload, PVC, and both edge routes without reading
Secret data:

```sh
kubectl --context calcifer-home -n home-assistant get certificate,deploy,pod,pvc,service,ingressroute
kubectl --context calcifer-cloud -n home-assistant get certificate,service,endpointslice,serverstransport,ingressroute
```

From a LAN client, `home.calcifer.tech` must resolve to `192.168.0.102`. From a
public resolver it must resolve to `136.144.222.128`. In both cases, verify the
Home Assistant UI and `/api/websocket` use valid TLS and remain connected.
Confirm that Home Assistant appears with a healthy status dot on the Homepage
dashboard at `https://calcifer.tech` from both LAN and public networks.

## Initial onboarding and authentication

On a fresh PVC, complete Home Assistant's one-time onboarding from a LAN client
and create the native owner account. Store those credentials as the emergency
break-glass account; Home Assistant does not permit OIDC to replace this initial
owner bootstrap.

After onboarding, normal visits redirect to Calcifer Authorization Server via
OIDC. To use the local break-glass login while the identity provider is
unavailable, open
`https://home.calcifer.tech/?skip_oidc_redirect=true` from the LAN.

HACS is installed declaratively by the `install-hacs` init container from a
pinned release with SHA-256 verification. The container installs it only when
`/config/custom_components/hacs/manifest.json` is absent, so HACS updates made
from its UI persist in the PVC. After the first deployment, restart Home
Assistant if needed, perform a hard browser refresh, then add HACS from
Settings > Devices & services and complete its GitHub device authentication.

Home Assistant 2026.9 stores reverse-proxy settings in `.storage/http` rather
than `configuration.yaml`. The `configure-http` init container maintains the
trusted Traefik networks as stable configuration before Core starts, preventing
the five-minute pending-configuration rollback. Do not add the deprecated
`http:` block back to `configuration.yaml`.

## Backup operations

The `home-assistant-backup` CronJob runs daily at 02:30 Europe/Rome. It copies
the configuration into an ephemeral staging volume, creates a consistent
SQLite backup through the SQLite backup API, verifies database integrity, and
stores the snapshot in the encrypted Restic repository under the private Azure
Blob container `home-assistant-backups`. It retains seven daily snapshots.

Restic sends HTTPS through a restricted HTTP CONNECT proxy bound only to
`172.31.255.1:3128` on `calcifer-cloud`. The proxy accepts only
`172.31.255.2` over WireGuard and only tunnels to
`calciferobs.blob.core.windows.net:443`; TLS remains end-to-end between Restic
and Azure. The storage account firewall therefore needs only the static Cloud
VPS egress IP `136.144.222.128`. Do not allow the dynamic Home egress IP or
open the storage account to unrestricted public access.

Provision or rotate the container-scoped SAS and encrypted Secret from an
authenticated administrative workstation. The script preserves the existing
Restic repository password during SAS rotation:

```sh
python3 scripts/provision-home-assistant-backup.py
```

After reconciliation, trigger a backup and inspect only its status and logs;
never print the Secret or its environment variables:

```sh
kubectl --context calcifer-home -n home-assistant create job \
  --from=cronjob/home-assistant-backup home-assistant-backup-manual
kubectl --context calcifer-home -n home-assistant wait \
  --for=condition=complete job/home-assistant-backup-manual --timeout=2h
kubectl --context calcifer-home -n home-assistant logs \
  job/home-assistant-backup-manual -c restic
```

Delete the manual Job after recording its outcome. A successful run must show
one new snapshot and successful retention processing.

If the job cannot reach Azure, first verify the private proxy and WireGuard
path without printing credentials:

```sh
kubectl --context calcifer-cloud -n private-transit get deploy,pod \
  -l app.kubernetes.io/name=azure-backup-egress-proxy
kubectl --context calcifer-cloud -n private-transit logs \
  deploy/azure-backup-egress-proxy --tail=100
```

## Restore and disaster recovery

Restore into a staging directory or replacement PVC first; never restore over
a running Home Assistant instance. Use a temporary administrative pod with the
same pinned Restic image, repository URL, and Secret references as the CronJob,
then run `restic restore <snapshot-id> --target /restore`. Do not place SAS or
repository passwords in command arguments, terminal history, or logs.

Before promotion:

1. Confirm the restored tree contains `snapshot/config` and expected `.storage`
   and configuration files.
2. Open the restored `home-assistant_v2.db` with Python `sqlite3` and require
   `PRAGMA integrity_check` to return `ok`.
3. Scale `deployment/home-assistant` to zero and confirm its pod has stopped.
4. Preserve the current PVC contents, then copy the staged `snapshot/config`
   contents into the PVC root without copying Restic credentials.
5. Reconcile or scale the Deployment back to one replica and watch startup
   logs without exposing integration credentials.

Verify the restored UI, OIDC login and local-auth fallback, automations,
integrations, entity/device registries, history, and `/api/websocket` from both
LAN and public paths. Keep the previous PVC contents until these checks pass.
If validation fails, stop Home Assistant, restore the preserved contents, and
reconcile again. A disaster-recovery exercise is complete only after a real
remote snapshot has been restored and all checks have passed.
