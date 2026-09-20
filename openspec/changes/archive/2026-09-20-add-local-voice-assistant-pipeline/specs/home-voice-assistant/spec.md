# Spec Delta

## Purpose

Fornisce una pipeline vocale locale in italiano che collega un PC Linux a Home
Assistant e agli adapter Azure residenti nel cluster `calcifer-home`, dalla
wake word alla risposta audio.

## ADDED Requirements

### Requirement: Cluster provides Azure speech services

Il sistema SHALL fornire nel cluster `calcifer-home` adapter Wyoming STT e TTS
per Azure Speech configurati per l'italiano. I servizi SHALL essere raggiungibili
da Home Assistant sulla rete interna del cluster e MUST NOT essere pubblicati su
Internet. Whisper e Piper SHALL NOT essere distribuiti da questa change.

#### Scenario: Speech services are available after deployment

- **WHEN** Home Assistant connects to the configured Wyoming STT and TTS endpoints
- **THEN** entrambi gli endpoint SHALL accettare il protocollo Wyoming e
  SHALL fornire un servizio vocale italiano tramite Azure Speech

#### Scenario: Speech services recover after restart

- **WHEN** either speech service pod is restarted
- **THEN** il servizio SHALL recuperare usando Secret e configurazione
  dichiarativa senza richiedere configurazione manuale

### Requirement: Azure Speech egress uses the Cloud static address

Le connessioni degli adapter SHALL usare l'endpoint nominativo della risorsa
Azure Speech e un proxy CONNECT dedicato su `calcifer-cloud`, raggiunto tramite
il tunnel WireGuard. Il proxy MUST accettare soltanto la sorgente Home sul
tunnel e MUST consentire soltanto l'endpoint Azure Speech dichiarato. La risorsa
Azure MUST negare per default la rete pubblica e consentire soltanto l'IP
pubblico statico di `calcifer-cloud`.

#### Scenario: Azure speech backend is selected

- **WHEN** Home Assistant usa gli endpoint Wyoming configurati
- **THEN** l'audio attivato SHALL essere trascritto come `it-IT` e la risposta
  SHALL essere sintetizzata con una voce italiana attraverso Azure Speech
- **AND** la connessione Azure SHALL uscire dall'indirizzo pubblico Cloud

#### Scenario: Direct Home access is rejected

- **WHEN** un client usa direttamente l'endpoint nominativo Azure dall'egress
  pubblico di `calcifer-home`
- **THEN** il firewall Azure SHALL rifiutare la richiesta

#### Scenario: Azure credentials remain secret

- **WHEN** i manifest del voice assistant sono archiviati nel repository
- **THEN** regione e subscription key SHALL essere lette da un Secret SOPS e la
  subscription key MUST NOT apparire in chiaro nei manifest versionati

### Requirement: Linux PC acts as a local voice satellite

Il sistema SHALL permettere a un PC Linux con microfono e altoparlanti di
operare come satellite vocale. La wake word SHALL essere rilevata localmente e
il PC MUST send microphone audio to Home Assistant only after activation, using
the ESPHome-compatible satellite API. The satellite MUST play received TTS
audio through the selected local audio output.

#### Scenario: Wake word gates microphone streaming

- **WHEN** the configured wake word is not detected
- **THEN** microphone audio SHALL remain local to the PC
- **WHEN** the wake word is detected
- **THEN** the satellite SHALL open an Assist conversation and stream the
  subsequent utterance to Home Assistant

#### Scenario: Satellite returns to local listening

- **WHEN** Home Assistant finishes the conversation and the response audio has
  been played
- **THEN** the satellite SHALL return to local wake-word listening without
  requiring a manual restart

### Requirement: Home Assistant provides an Italian Assist pipeline

Home Assistant SHALL expose a configured Assist pipeline that uses selectable
Wyoming Azure STT/TTS services for Italian speech, Home Assistant's
conversation/intent engine for command execution, and a spoken response. The
pipeline SHALL be selectable by the LVA satellite integration.

#### Scenario: Spoken switch command completes end to end

- **WHEN** a user activates the satellite and says an Italian command to turn on
  the configured test switch
- **THEN** Home Assistant SHALL transcribe the utterance, execute the switch
  action, synthesize the response, and send audio back to the same satellite

#### Scenario: STT or TTS is unavailable

- **WHEN** either configured speech service is unavailable
- **THEN** the pipeline SHALL fail the conversation with an observable error and
  MUST NOT report the switch action as successfully completed

### Requirement: Test switch command speaks the exact confirmation

The system SHALL provide a documented Italian test sentence/intent that targets
one explicitly selected Home Assistant switch entity and returns the exact
  spoken confirmation `Fatto` after the switch action succeeds. The confirmation
  MUST be synthesized by the TTS service selected in the active pipeline.

#### Scenario: Successful test command

- **WHEN** the user speaks the documented test phrase and the selected switch
  action succeeds
- **THEN** the satellite SHALL play the single confirmation text `Fatto`

#### Scenario: Test switch action fails

- **WHEN** the selected switch entity rejects the action or is unavailable
- **THEN** Home Assistant SHALL not play the success confirmation `Fatto` and
  SHALL expose the failure to the user

### Requirement: Local development setup is reproducible and secret-safe

The repository SHALL contain a Docker Compose setup under
`dev/linux-voice-assistant/` that can run the Linux satellite on a Linux host
with PipeWire's PulseAudio-compatible socket. The repository SHALL include a
version-controlled `.env.example`, SHALL ignore the corresponding real `.env`,
and MUST NOT require credentials or host-specific secrets to be committed.

#### Scenario: Developer prepares the satellite configuration

- **WHEN** a developer copies `.env.example` to `.env` and supplies the local
  UID/GID and audio runtime paths
- **THEN** the Compose project SHALL start the satellite with persistent
  wake-word/configuration volumes and access to the host audio server

#### Scenario: Repository safety check

- **WHEN** the repository is checked for tracked files
- **THEN** the local `.env`, audio cookies, and generated model/configuration
  data SHALL not be tracked

### Requirement: Voice pipeline and restricted Cloud egress are validated

The implementation SHALL document a repeatable validation and rollback
  procedure covering the local Compose satellite, the Home cluster speech
  services, Home Assistant onboarding, one real switch command, and the
  restricted Cloud egress. The change MUST NOT expose a public voice endpoint or
  a general-purpose forward proxy on `calcifer-cloud`.

#### Scenario: End-to-end validation passes

- **WHEN** the documented validation procedure is run with the selected switch
  available
- **THEN** it SHALL demonstrate wake-word detection, Italian transcription,
  switch activation, the exact `Fatto` response, and local audio playback

#### Scenario: Voice capability is rolled back

- **WHEN** the voice application is removed or disabled
- **THEN** Home Assistant's existing HTTP workload and persistent configuration
  SHALL remain available, and the local satellite SHALL stop without affecting
  either cluster's unrelated workloads