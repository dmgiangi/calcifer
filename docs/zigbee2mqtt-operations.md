# Zigbee2MQTT operations

## Web frontend and routing

The frontend is available at `https://zigbee.calcifer.tech` from the Home LAN
and Internet. Home Traefik routes to the OAuth2 Proxy sidecar in
`home-automation`; Cloud Traefik forwards HTTPS from `web` over WireGuard with
canonical SNI. Both edges require the `admin` role from the authorization
server. The unprotected Zigbee2MQTT application port remains cluster-internal.
A restricted LAN endpoint for MQTT is prepared but not yet deployed; see below.

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

## Therment LAN MQTT

Therment is intended to connect to `192.168.0.102:1883` over TCP. The prepared
separate host-network HAProxy will bind only that Home node address and reject
connections whose source is outside `192.168.0.0/24`, then forward to the
existing ClusterIP Service. It is pinned to `calcifer-home`; a node address or
hostname change requires updating the manifest. Increment its pod-template
`checksum/config` annotation when changing its ConfigMap so Flux rolls it out.
The broker and existing internal clients retain their cluster DNS endpoint.

MQTT is currently **unencrypted**: credentials, commands, and data travel in
clear text on the LAN. Anyone able to observe that network can capture them.
The source check is not authentication and cannot distinguish clients within
the subnet or prevent source spoofing. Do not forward port 1883 on the router,
and do not SNAT traffic from other networks into the allowed subnet. Confirm
router, VLAN and host firewall rules and test from outside the LAN before use;
a proxy source check cannot detect an upstream NAT that hides the real source.
The node's existing WireGuard ingress rules drop non-HTTPS new traffic, but
router configuration and every external path must be checked independently.

The dedicated username is `therment`. Its credentials are in the SOPS-encrypted
`clusters/calcifer-home/apps/home-automation/mqtt/therment-credentials.sops.yaml`.
On a trusted administrator workstation with the repository age key, retrieve
them into a private file for provisioning (not into a terminal, shell history,
or firmware committed to Git):

```sh
umask 077
private_dir=$(mktemp -d "${TMPDIR:-/tmp}/therment-mqtt.XXXXXXXX")
sops -d clusters/calcifer-home/apps/home-automation/mqtt/therment-credentials.sops.yaml |
  python3 -c 'import sys,yaml; d=yaml.safe_load(sys.stdin)["stringData"]; yaml.safe_dump(d,sys.stdout)' > "$private_dir/credentials.yaml"
chmod 600 "$private_dir/credentials.yaml"
```

Transfer via an approved secure provisioning channel and securely remove the
local file when no longer needed. Rotate the SOPS secret if exposed; restart
the broker after a secret update because the init container generates its
password file only at pod start. Never log the decrypted data.

### Proposed Therment contract and ACLs (not yet deployed)

Initial device ID: `therment-01`. Other IDs must be stable, unique, and contain
no `/`, `+`, or `#`. Payloads are UTF-8; temperatures are degrees Celsius.
The firmware contract is defined in the Therment project; integration still
requires end-to-end validation with the device and Home Assistant.

| Exact topic | Therment | Home Assistant | Payload and retain |
| --- | --- | --- | --- |
| `homeassistant/climate/therment-01/config` | write | read | discovery JSON; retained |
| `therment/therment-01/temperature` | write | read | numeric temperature or literal `None` when invalid; retained |
| `therment/therment-01/state` | write | read | JSON, e.g. `{"setpoint":16.0,"mode":"auto","action":"cooling","sensor":"ok"}`; retained |
| `therment/therment-01/availability` | write | read | `online` or `offline`; retained |
| `therment/therment-01/setpoint/set` | read | write | numeric requested setpoint; not retained |
| `therment/therment-01/mode/set` | read | write | `auto` or `off`; not retained |

`mode` is the applied `auto` or `off` mode; `off` turns both relays off
and reports `action: idle`. The firmware persists the mode and defaults to
`off` if no valid stored value exists. It reports the current mode after every
accepted or rejected command. `auto` resumes regulation with the saved
setpoint and compressor delay.

`action` is the actual relay output (`heating`, `cooling`, or `idle`), not a
pending request. `sensor` is `ok` or `error`. On sensor failure firmware must
turn both relays off and publish `sensor: error`, `action: idle` and the
literal temperature payload `None`. The installed Home Assistant 2026.9.2
MQTT climate code explicitly resets its current temperature on `None`; this
still needs an end-to-end test with the discovery payload and device.

Accept setpoints 5–30 °C inclusive in 0.5 °C increments. Reject malformed,
non-finite, out-of-range and misaligned values, retaining the old setpoint.
After either acceptance and persistence or rejection, republish the actual
state. Persist changes across restart, default to 16 °C if no valid value is
stored, and avoid unnecessary flash writes. Home Assistant must use matching
limits and step, `retain: false` for commands, `optimistic: false`, a
`temperature_state_topic` and `temperature_state_template` extracting
`value_json.setpoint`, and an `action_topic` and `action_template` extracting
`value_json.action`. The current temperature topic must pass `None` unchanged.
Availability uses the stated topic and `online`/`offline` payloads.

Configure a retained `offline` Last Will, publish `online` only when ready,
and, after every reconnect, restore the subscription and republish discovery,
state, temperature (including `None`), and availability. Publish `offline`
before orderly disconnect when possible. Regulation remains autonomous without
MQTT: preserve hysteresis, relay interlock, sensor-failure shutdown and minimum
compressor delay including after restart.

### Candidate Home Assistant discovery payload

The following payload fixes the mapping of the proposed contract without adding
firmware topics. Publish it retained to the discovery topic only after resolving
the control limitation below and testing with the installed integration:

```json
{
  "name": "Therment",
  "unique_id": "therment-01-climate",
  "device": {"identifiers": ["therment-01"], "name": "Therment"},
  "current_temperature_topic": "therment/therment-01/temperature",
  "temperature_state_topic": "therment/therment-01/state",
  "temperature_state_template": "{{ value_json.setpoint }}",
  "temperature_command_topic": "therment/therment-01/setpoint/set",
  "action_topic": "therment/therment-01/state",
  "action_template": "{{ value_json.action }}",
  "availability_topic": "therment/therment-01/availability",
  "payload_available": "online",
  "payload_not_available": "offline",
  "mode_state_topic": "therment/therment-01/state",
  "mode_state_template": "{{ value_json.mode }}",
  "mode_command_topic": "therment/therment-01/mode/set",
  "modes": ["auto", "off"],
  "min_temp": 5,
  "max_temp": 30,
  "temp_step": 0.5,
  "temperature_unit": "C",
  "optimistic": false,
  "retain": false
}
```

The mode template reports the firmware-confirmed mode; `action` reports the
actual relay state. The state topic confirms both setpoint and mode, so an
unacknowledged command does not change the displayed value. An invalid-sensor
`None` payload resets current temperature rather than preserving the previous
reading. Home Assistant's `auto`/`off` controls send the corresponding mode
commands; test the interface with the actual firmware before relying on it.

Mosquitto ACLs are **not enabled**: authenticated users currently have broad
topic access, including Therment. Enabling an ACL changes defaults for every
client. The proposed exact Therment grants are the six rows above. Home
Assistant needs discovery and state reads and command writes, including its
other MQTT integrations and birth/discovery lifecycle. Zigbee2MQTT needs its
configured `zigbee2mqtt/#` and Home Assistant discovery paths, and the
Zigbee2MQTT account used by broker probes must publish `health/mosquitto`.
Inventory all clients and subscriptions, retained discovery removal and birth
messages, then test a candidate ACL with the real integrations before enabling
it. The currently observed traffic is insufficient to establish an exhaustive
allowlist.

After GitOps reconciliation, check the proxy and broker deployments and their
logs without displaying credentials. From an authorized LAN host, authenticate
with the dedicated account and verify a test publish/subscribe on the agreed
Therment topics; verify Home Assistant and Zigbee2MQTT remain connected. From a
different subnet, WireGuard, and an Internet client, test that TCP 1883 cannot
reach the MQTT broker, and inspect router port forwards and SNAT rules. A TCP
handshake alone is insufficient to verify the HAProxy source rejection: attempt
an MQTT exchange and confirm it is rejected. Do not send production commands
or publish retained discovery until the firmware contract is agreed.
