# add-local-voice-assistant-pipeline

Pipeline vocale completa: wake word locale su Linux Voice Assistant, Azure
Speech STT/TTS su `calcifer-home` e egress ristretto via `calcifer-cloud`.

## Runtime inputs and pinned versions

- Home Assistant entity: `switch.luce_cucina`; service: `switch.turn_on`.
- Successful spoken confirmation: `Fatto`.
- PC LAN address for manual ESPHome onboarding: `192.168.0.100`.
- Local wake word: `Calcifer`.
- `calcifer-home`: 4 CPU, approximately 7.6 GiB RAM, `local-path` storage.
- LVA: `ghcr.io/ohf-voice/linux-voice-assistant:1.1.15`.
- Azure STT: `ghcr.io/hugobloem/wyoming-microsoft-stt-noha:1.3.10`,
  language `it-IT`, region `italynorth`.
- Azure TTS: `ghcr.io/hugobloem/wyoming-microsoft-tts-noha:1.4.5`,
  voice `it-IT-IsabellaNeural`, region `italynorth`.

All image references include immutable SHA-256 digests. Speech traffic uses the
dedicated Cloud CONNECT proxy at `172.31.255.1:3129`; the proxy accepts only the
Home WireGuard address and the named Azure Speech endpoint.

## Startup and Home Assistant onboarding

1. Reconcile the Home application and wait for all speech deployments:
   `kubectl -n voice-assistant get pods`.
2. In Home Assistant add **Wyoming Protocol** for Azure:
   - STT host `wyoming-azure-stt.voice-assistant.svc.cluster.local`, port `10300`;
   - TTS host `wyoming-azure-tts.voice-assistant.svc.cluster.local`, port `10200`.
3. Create/select an Italian Assist pipeline with Microsoft STT `it-IT` and
   Microsoft TTS `it-IT-IsabellaNeural`.
4. Add the LVA satellite through the ESPHome integration using host
   `192.168.0.100` and port `6053`; do not depend on multicast discovery.
5. Select the Italian pipeline for the LVA device.

The Azure Speech key and region are read from the SOPS-encrypted Secret
`azure-speech`; never place the key in Home Assistant fields, command arguments,
logs, or unencrypted repository files. Azure network access denies by default
and allows only the public egress IP of `calcifer-cloud`.

The documented test utterance is **“Accendi la luce cucina”**. It targets
`switch.luce_cucina` through `switch.turn_on`; on success the custom intent
must speak exactly `Fatto`. Test the unavailable-switch path separately and
confirm that it does not speak the success confirmation.

## Rollback

- Disable or remove only `clusters/calcifer-home/apps/voice-assistant` from the
  Home application Kustomization and reconcile Flux.
- Remove the dedicated Cloud Speech proxy only after the Home adapters have
  been disabled. The existing Home Assistant Deployment, PVC, HTTP Service,
  WireGuard tunnel, backup proxy, and unrelated workloads remain outside this
  rollback.
