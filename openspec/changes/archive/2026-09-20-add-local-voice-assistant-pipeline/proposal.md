# Proposal

## Why

Home Assistant è già raggiungibile dal browser, ma non dispone ancora di una
pipeline vocale locale completa. Serve un percorso riproducibile che mantenga la
wake word sul PC Linux, invii audio solo dopo l'attivazione e usi il cluster
`calcifer-home` per STT, automazione e TTS, così da poter comandare uno switch e
ricevere una risposta vocale senza hardware satellite dedicato.

## What Changes

- Aggiungere una capability `home-voice-assistant` per la pipeline vocale
  end-to-end in italiano.
- Aggiungere sotto `dev/linux-voice-assistant/` un Docker Compose locale per
  Linux Voice Assistant (LVA), con immagine versionata, accesso a PipeWire
  tramite il socket PulseAudio, wake word locale e volumi persistenti per dati e
  configurazione.
- Fornire `.env.example` versionato e ignorare il `.env` locale, senza inserire
  credenziali, cookie o indirizzi personali nel repository.
- Distribuire su `calcifer-home` adapter Wyoming interni per Azure Speech
  STT/TTS come unico backend vocale, con credenziali SOPS e Service interni.
- Instradare le connessioni Azure Speech tramite un proxy CONNECT dedicato su
  `calcifer-cloud`, raggiungibile soltanto attraverso WireGuard e limitato
  all'endpoint nominativo della risorsa Speech.
- Collegare Home Assistant ai server Wyoming e al satellite LVA tramite le
  integrazioni supportate, quindi creare una pipeline Assist in italiano.
- Configurare una frase/intento di prova che attivi uno switch Zigbee selezionato e
  produca esattamente la risposta vocale `Fatto` tramite Azure Speech.
- Documentare onboarding, prerequisiti, verifica end-to-end e rollback del
  percorso di egress ristretto attraverso `calcifer-cloud`.

## Capabilities

### New Capabilities

- `home-voice-assistant`: disponibilità dei servizi STT/TTS nel cluster,
  integrazione del satellite locale, pipeline Assist in italiano, comando switch e
  risposta vocale.

### Modified Capabilities

Nessuna. I requisiti esistenti di `home-assistant-workload` restano invariati;
questa change aggiunge la capability vocale senza cambiare il contratto del
deployment HTTP di Home Assistant.

## Impact

- Nuovi artefatti locali sotto `dev/linux-voice-assistant/`:
  `docker-compose.yml` e `.env.example`.
- Nuovi manifest Kustomize/Flux sotto
  `clusters/calcifer-home/apps/voice-assistant/`, inclusi namespace,
  Deployment, Service, ConfigMap e inclusione nella Kustomization delle
  applicazioni Home.
- Configurazione e onboarding di Home Assistant sul PVC esistente, inclusi i
  server Wyoming, la pipeline Assist e l'intento di test.
- Dipendenze runtime esterne: immagini LVA e Wyoming più Azure Speech; immagini
  e versioni dovranno essere pin-nate durante
  l'implementazione e la chiave Azure dovrà restare cifrata con SOPS.
- Un proxy Envoy dedicato nel private transit di `calcifer-cloud`, senza endpoint
  STT/TTS pubblicati su Internet. Gli adapter Azure possono raggiungere soltanto
  l'endpoint Speech autorizzato e Azure accetta soltanto l'IP pubblico Cloud.