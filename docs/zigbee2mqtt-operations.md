# Zigbee2MQTT operations

## Web frontend and routing

The frontend is available at `https://zigbee.calcifer.tech` from the Home LAN
and Internet. Home Traefik routes to the OAuth2 Proxy sidecar in
`home-automation`; Cloud Traefik forwards HTTPS from `web` over WireGuard with
canonical SNI. Both edges require the `admin` role from the authorization
server. MQTT and the unprotected application port remain cluster-internal.

Verify both paths without reading Secret data:

```sh
kubectl --context calcifer-home -n home-automation get certificate,deploy,pod,service,ingressroute
kubectl --context calcifer-cloud -n web get certificate,service,endpointslice,serverstransport,ingressroute
```

## Broker and coordinator

- Broker: `mqtt://mosquitto.home-automation.svc.cluster.local:1883`
- Base topic: `zigbee2mqtt`
- Home Assistant discovery: enabled
- Credentials: `clusters/calcifer-home/apps/home-automation/mqtt/mosquitto-credentials.sops.yaml`

The coordinator is attached only to the Zigbee2MQTT Deployment on
`calcifer-home` through its stable `/dev/serial/by-id` host path. Never run ZHA
or a second Zigbee2MQTT instance against it. The privileged Zigbee2MQTT
container is required by the K3s/containerd device cgroup; other containers
remain unprivileged.

## Backup and restore

The `zigbee2mqtt-backup` CronJob runs daily at 03:00 Europe/Rome, snapshots the
PVC with checksums, uploads to the encrypted Restic repository, checks repository
data, retains seven daily snapshots, and uses only the restricted Cloud CONNECT
proxy at `172.31.255.1:3128`.

Trigger and verify a backup without printing Secret values:

```sh
kubectl --context calcifer-home -n home-automation create job \
  --from=cronjob/zigbee2mqtt-backup zigbee2mqtt-backup-manual
kubectl --context calcifer-home -n home-automation wait \
  --for=condition=complete job/zigbee2mqtt-backup-manual --timeout=2h
kubectl --context calcifer-home -n home-automation logs \
  job/zigbee2mqtt-backup-manual -c restic
```

For recovery, scale Zigbee2MQTT to zero and restore an explicitly recorded
snapshot ID into staging, never directly over the live PVC. Verify
`snapshot/SHA256SUMS` and the configuration, coordinator, network, and device
state files before copying `snapshot/data` to the empty replacement PVC. Start
Mosquitto first, then Zigbee2MQTT, and confirm `zigbee2mqtt/bridge/state`,
retained discovery topics, and Home Assistant MQTT entities without re-pairing.
