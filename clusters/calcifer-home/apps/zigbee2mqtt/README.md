# Home Zigbee2MQTT

## Web frontend

The administration frontend is available at `https://zigbee.calcifer.tech`
from both the Home LAN and Internet. LAN DNS resolves the name to
`192.168.0.102`, where Home Traefik routes only to the OAuth2 Proxy sidecar.
Public DNS resolves the same name to Cloud `136.144.222.128`; Cloud Traefik
forwards HTTPS over WireGuard to Home `172.31.255.2:443` with canonical SNI.

Both edges use publicly trusted certificates. OAuth2 Proxy requires the
`admin` role from `https://auth.calcifer.tech`. Port 8080 is not published by a
Service, MQTT remains cluster-internal, and the Home router has no inbound
port-forward.

Verify both paths without reading Secret data:

```sh
kubectl --context calcifer-home -n zigbee2mqtt get certificate,deploy,pod,service,ingressroute
kubectl --context calcifer-cloud -n zigbee2mqtt get certificate,service,endpointslice,serverstransport,ingressroute
```

## Broker endpoints

- In-cluster clients: `mqtt://mosquitto.mqtt.svc.cluster.local:1883`
- Zigbee2MQTT base topic: `zigbee2mqtt`
- Home Assistant discovery: enabled

The Home Assistant MQTT integration should use the in-cluster endpoint,
username `homeassistant`, and the password stored in the SOPS encrypted
`clusters/calcifer-home/apps/mqtt/mosquitto-credentials.sops.yaml` resource.
Zigbee2MQTT uses its separate `zigbee2mqtt` account from the same encrypted
Secret.

## Coordinator

Zigbee2MQTT is restricted to `calcifer-home` and uses the stable host device
path for the SONOFF Dongle Plus CC2674P10. The container exposes that character
device at `/dev/ttyUSB0` and uses the TI Z-Stack adapter. Do not run ZHA or a
second Zigbee2MQTT instance against the coordinator.

The raw device mount requires a privileged Zigbee2MQTT container on this K3s
containerd runtime because a plain `hostPath` character-device mount is blocked
by the device cgroup. Mosquitto and other workloads remain unprivileged.

## Recovery

1. Check Mosquitto and Zigbee2MQTT pod logs before restarting either workload.
2. Restart Mosquitto first, then wait for its authenticated listener before
   restarting Zigbee2MQTT.
3. Preserve `mosquitto-data` and `zigbee2mqtt-data` PVCs during normal rollback.
4. Never attach the coordinator to another workload while Zigbee2MQTT is
   running.
5. After recovery, verify the `zigbee2mqtt/bridge/state` topic and Home
   Assistant discovery entities.

The coordinator backup is stored in the Zigbee2MQTT data PVC. Keep a copy of
that PVC before intentionally changing coordinator firmware or network state.
