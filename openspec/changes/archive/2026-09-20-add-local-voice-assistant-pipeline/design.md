# Design

## Context

See `proposal.md` for the motivation and scope. `calcifer-home` already runs a
single-replica Home Assistant workload managed by Flux and Kustomize, with
persistent configuration and internal ClusterIP Services. The developer PC is
Pop!_OS 24.04 with Docker, UID/GID `1000`, and PipeWire exposing a PulseAudio
socket at `/run/user/1000/pulse/native`.

The voice path crosses multiple trust and lifecycle boundaries: the PC is a
local development host outside Kubernetes, Home Assistant and Wyoming adapters
run in the Home cluster, and the speech backend is Azure Speech. The
PC must therefore initiate the satellite connection over the LAN, Kubernetes
must not manage the PC process, and only activated utterances may leave the Home
network for Azure.

## Goals / Non-Goals

**Goals:**

- Keep wake-word detection and idle microphone processing on the Linux PC.
- Use Azure Speech as the only Italian STT/TTS backend exposed through internal
  Wyoming services on `calcifer-home`.
- Send Azure Speech traffic through the static public egress of
  `calcifer-cloud`, with least-privilege destination filtering.
- Connect Home Assistant to the services using supported Wyoming and ESPHome
  integrations rather than custom protocol bridges.
- Provide a deterministic Italian test command whose successful spoken response
  is exactly `Fatto`.
- Make the local satellite setup reproducible, parameterized, and safe to
  commit.
- Keep all voice service exposure private to the cluster or the LAN path needed
  by the satellite.

**Non-Goals:**

- Deploying the satellite container in Kubernetes.
- Adding a public voice endpoint or a general-purpose Cloud forward proxy.
- Replacing Home Assistant's existing authentication, HTTP ingress, backup, or
  database design.
- Supporting multiple rooms, multiple satellites, or a generalized custom
  conversational agent in the first iteration.
- Guaranteeing voice operation when the PC, LAN, Home Assistant, or cluster is
  unavailable.

## Decisions

### Dedicated Home Azure voice application

Create a `voice-assistant` application under the Home cluster applications
root, with a dedicated namespace and one Wyoming Azure Deployment for each of
STT and TTS. Use one replica per service because the cluster is a small home
installation.

Use the standard Wyoming TCP ports: `10300` for STT and `10200` for TTS.
Services remain ClusterIP-only. Home Assistant connects through stable Service
DNS names, avoiding pod IPs and avoiding an external load balancer.

Images SHALL be pinned to release tags and immutable digests during
implementation. STT is configured for `it-IT`; TTS uses
`it-IT-IsabellaNeural`. Whisper, Piper, and their model PVC are removed.

### Azure speech adapters and explicit SDK bootstrap

Run dedicated Wyoming Microsoft STT and TTS Deployments in `calcifer-home`,
exposed only through ClusterIP Services on ports `10300` and `10200`. Configure
STT for `it-IT`, TTS for `it-IT-IsabellaNeural`, and Azure region `italynorth`.
Pin both images to release tags and immutable digests.

Both workloads read `AZURE_SERVICE_REGION` and `AZURE_SUBSCRIPTION_KEY` from one
SOPS-encrypted Secret. They use the named endpoint
`https://calcifer-home-speech-it.cognitiveservices.azure.com/`; no Azure key is
passed as a command argument, committed in clear text, or exposed through a
public Kubernetes Service.

The upstream adapter versions expose no proxy option, and the native Speech SDK
does not honor `HTTP_PROXY`/`HTTPS_PROXY` in this runtime. A ConfigMap-mounted
Python bootstrap therefore replaces adapter construction of `SpeechConfig` with
an endpoint configuration followed by `set_proxy`. The bootstrap allows only
the two adapter modules and leaves the subscription key in the SOPS Secret.

The TTS container uses `/tmp` as its working directory as well as its writable
volume because upstream creates synthesis output paths relative to the process
working directory. The remainder of the container filesystem stays read-only.

### Restricted Speech egress through Cloud

Add a dedicated Envoy HTTP CONNECT proxy to the existing `private-transit`
application on `calcifer-cloud`. It binds only to `172.31.255.1:3129`, accepts
only WireGuard peer `172.31.255.2`, and routes only CONNECT requests for
`calcifer-home-speech-it.cognitiveservices.azure.com:443`. Cloud WireGuard
firewall rules permit that port only from Home. This proxy is separate from the
Azure Blob backup proxy and is not general purpose.

Because the voice pods do not use `hostNetwork`, Home applies a destination- and
port-specific SNAT rule for `172.31.255.1:3129`, translating only this traffic to
the permitted WireGuard peer address `172.31.255.2`.

Azure Speech public access remains enabled with `defaultAction: Deny` and one
public IP rule for static Cloud address `136.144.222.128`. The named resource
endpoint ensures the resource firewall applies to the speech requests.

### Local Linux Voice Assistant Compose

Use the upstream LVA Compose structure at the selected release, adapted only
for repository-local development. Run the main service with host networking so
the ESPHome-compatible API is reachable from Home Assistant and so local
audio-server behavior matches the host. Run it as the host user's UID/GID,
pass the XDG runtime directory, PulseAudio server socket, and PulseAudio
cookie path through `.env`, and persist LVA configuration, wake-word data, and
custom sounds in named volumes.

The Compose file SHALL use a versioned LVA image rather than `latest`. The
`.env.example` SHALL default to the verified Pop!_OS values but keep them
overridable for another developer. The real `.env` is ignored, and no audio
cookie is copied into the repository.

### Home Assistant onboarding

Add the Wyoming integration through Home Assistant's supported configuration
flow using the in-cluster Azure Service endpoints. Add the LVA device through the
ESPHome integration using the PC's LAN-reachable address and API port `6053`;
use manual addressing if multicast discovery cannot cross the Kubernetes/LAN
boundary. Create an Italian Assist pipeline selecting the Azure STT/TTS
adapters, then select the active pipeline for the LVA device.

No Home Assistant HTTP configuration change is required merely to expose the
voice services. Any declarative custom sentence/intent files needed for the
exact test response are mounted through the existing Home Assistant
configuration mechanism and remain separate from the generated runtime state.

### Exact `Fatto` response

Implement the first validation command as a narrowly scoped custom Italian
sentence/intent bound to one operator-selected switch entity. The action calls
the `switch.turn_on` service, and its success speech is the exact text `Fatto`;
failures use Home Assistant's error path and cannot emit the success speech. Keep normal
built-in Assist intents available for other commands, but do not rely on their
localized default wording for this acceptance test.

### Validation and rollback

Validate in layers: render and statically validate Kustomize resources, verify
pod readiness and internal Wyoming connectivity, configure Home Assistant,
verify LVA audio devices and API reachability, prove that direct Home access to
the named Azure endpoint is denied, prove that proxied STT/TTS succeeds, then
run the wake-word-to-switch acceptance test. Record logs and command results
without exposing audio cookies or other secrets.

Rollback removes or suspends the Home voice application and disconnects the
Wyoming/ESPHome integrations if necessary. It leaves the Home Assistant PVC and
existing HTTP edge intact. Locally, `docker compose down` stops LVA while
named volumes may be retained for a later retry.

## Risks / Trade-offs

- **[Risk]** The cluster network may not be able to reach the PC's LAN address,
  or multicast discovery may not cross the boundary. **Mitigation:** verify the
  route and TCP port explicitly, use the PC's stable LAN address/manual
  ESPHome onboarding, and document the required firewall rule.
- **[Risk]** The Cloud proxy or WireGuard tunnel becomes unavailable and both
  speech services fail. **Mitigation:** keep the failure observable and document
  independent tunnel/proxy health checks.
- **[Risk]** Audio permissions or PipeWire session ownership can prevent a
  container running as UID 1000 from opening the microphone or speaker.
  **Mitigation:** pass the runtime directory and cookie, add the host audio
  group, validate with LVA's device listing, and never mount raw ALSA devices
  unless the PulseAudio compatibility path fails.
- **[Risk]** A custom sentence can conflict with built-in Italian intents or
  target the wrong switch. **Mitigation:** use a distinct documented test phrase,
  require an explicit entity selection during onboarding, and test both success
  and unavailable-switch paths.
- **[Risk]** Upstream images or wake-word assets can change behavior when tags
  float. **Mitigation:** pin tags and digests, persist downloaded assets, and
  record versions in the design/validation notes.
- **[Risk]** Azure rejects requests when the proxy path or Cloud IP rule is
  incorrect, or usage exceeds quota. **Mitigation:** authorize only the static
  Cloud address, test direct-path denial and proxied success, monitor usage, and
  never expose the subscription key in logs.
- **[Risk]** The PC is a development host and may be suspended or logged out.
  **Mitigation:** document the prerequisite for an active user audio session;
  defer system-service/autostart hardening to a later change.

## Migration Plan

1. Confirm the selected switch entity, LAN address of the PC, Azure Speech
   resource, and static Cloud public address.
2. Add the destination-restricted Cloud CONNECT proxy and WireGuard firewall
   rule, then verify it is reachable only through private transit.
3. Configure the Home Azure adapters with the named endpoint and explicit SDK
   proxy bootstrap; verify Wyoming connectivity from Home Assistant.
4. Start the local LVA Compose project, verify microphone/speaker devices and
   wake-word behavior, and keep the API reachable from Home Assistant.
5. Onboard the Azure Wyoming endpoints plus ESPHome in Home Assistant, create
   the Italian Assist pipeline, and install the exact-response test intent.
6. Verify direct Home denial, proxied Azure STT/TTS, and the acceptance test.
7. Remove Whisper/Piper and their PVC only after Azure validation. Roll back by
   disabling the voice Kustomization and then removing the Cloud Speech proxy.

## Resolved Runtime Inputs

- Acceptance-test target: `switch.luce_cucina`, controlled with
  `switch.turn_on`.
- PC LAN address for manual ESPHome onboarding: `192.168.0.94`.
- `calcifer-home` capacity verified as 4 CPU and approximately 7.6 GiB RAM;
  the default `local-path` StorageClass is available.
- Pinned images are recorded in the Kubernetes and Compose manifests; no
  `latest` tag is used by this change.
- Azure Speech resource: `calcifer-home-speech-it`, region
  `italynorth`; STT language `it-IT`, TTS voice `it-IT-IsabellaNeural`.
- Azure credentials are stored only in `azure-speech-secret.sops.yaml`; the
  Speech network allowlist contains only Cloud `136.144.222.128`, and the
  dedicated proxy is `172.31.255.1:3129` over WireGuard.