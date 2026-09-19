# Home Zigbee2MQTT

## Web frontend

The administration frontend is available at `https://zigbee.calcifer.tech` on
the Home LAN. Traefik terminates the public certificate and routes only to the
OAuth2 Proxy sidecar, which requires the `admin` role from
`https://auth.calcifer.tech`. Port 8080 is not published by a Service.

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
