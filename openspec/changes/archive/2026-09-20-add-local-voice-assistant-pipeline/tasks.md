# Tasks

## 1. Confirm versions and runtime inputs

- [x] 1.1 Confirm the Home Assistant switch entity, PC LAN address, Azure Speech
  resource and region, and static public address of `calcifer-cloud`; verify
  each value is recorded without exposing credentials
- [x] 1.2 Select stable release tags and immutable image digests for LVA and the
  Wyoming Azure adapters; verify no manifest or Compose file uses `latest`
- [x] 1.3 Confirm the existing Home/Cloud WireGuard private transit, tunnel
  addresses, namespace conventions, and destination-restricted backup proxy
  pattern before extending Cloud egress

## 2. Implement Azure speech services and restricted egress

- [x] 2.1 Add the SOPS-encrypted Azure Speech secret plus pinned Wyoming Azure
  STT/TTS Deployments and ClusterIP Services; verify Italian language/voice,
  immutable digests, and no public speech Service
- [x] 2.2 Add a dedicated Envoy CONNECT proxy on `calcifer-cloud` bound to
  `172.31.255.1:3129`; allow only the Home WireGuard source and the named Azure
  Speech endpoint, and add the corresponding Cloud tunnel firewall rule
- [x] 2.3 Add and validate the Home SDK bootstrap that selects the named Azure
  endpoint and configures `SpeechConfig.set_proxy` without exposing the key
- [x] 2.4 Remove Whisper, Piper, their Services, and model PVC from the Home
  Kustomization after proxied Azure STT/TTS validation succeeds
- [x] 2.5 Apply/reconcile both clusters; verify direct Home access to the named
  endpoint is denied, proxied Wyoming TTS/STT succeeds, and Azure permits only
  the static Cloud public address

## 3. Configure Home Assistant and the exact response

- [x] 3.1 Add the Azure Wyoming integration endpoints to Home Assistant and
  verify Italian STT and TTS are selectable in an Assist pipeline
- [x] 3.2 Add the LVA device through the ESPHome integration using the PC LAN
  address and port `6053`; verify Home Assistant can open the satellite session
  without relying on multicast discovery
- [x] 3.3 Create the Italian Assist pipeline and bind it to the LVA device;
  verify an utterance is transcribed and answered through Azure
- [x] 3.4 Add the documented custom Italian test sentence/intent for the
  selected switch entity, with success speech exactly `Fatto`; verify success
  and unavailable-switch paths do not confuse the response

## 4. Validate, document, and provide rollback

- [x] 4.1 Run the full acceptance test from wake word through Italian switch
  command, state change, selected TTS synthesis, and PC speaker playback; record the
  observable result and warm-path latency without recording secrets
- [x] 4.2 Validate both Kustomize outputs, pod/proxy logs,
  internal Service reachability, direct-path denial, proxied STT/TTS, and
  repository secret checks
- [x] 4.3 Document prerequisites, startup order, Home Assistant onboarding,
  restricted Cloud egress, firewall checks, troubleshooting, and rollback
- [x] 4.4 Exercise rollback by disabling the voice application; verify that
  Home Assistant HTTP access, its PVC, and unrelated Home cluster workloads
  remain available