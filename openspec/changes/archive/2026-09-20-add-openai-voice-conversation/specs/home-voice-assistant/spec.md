# Spec Delta

## MODIFIED Requirements

### Requirement: Linux PC acts as a local voice satellite

Il sistema SHALL permettere a un PC Linux con microfono e altoparlanti di operare come satellite vocale. La wake word SHALL essere rilevata localmente e il PC MUST send microphone audio to Home Assistant only after activation, using the ESPHome-compatible satellite API. The satellite MUST play received TTS audio through the selected local audio output. During a native continued AI conversation, the satellite SHALL temporarily accept follow-up speech without requiring a new wake word, then SHALL return to local wake-word listening when continuation ends.

#### Scenario: Wake word gates microphone streaming

- **WHEN** the configured wake word is not detected
- **THEN** microphone audio SHALL remain local to the PC
- **WHEN** the wake word is detected
- **THEN** the satellite SHALL open an Assist conversation and stream the subsequent utterance to Home Assistant

#### Scenario: Continued AI conversation keeps listening

- **WHEN** Home Assistant marks an AI response for native continued conversation
- **THEN** the satellite SHALL reopen listening for a follow-up utterance without requiring the wake word
- **AND** SHALL play the follow-up response through the selected local audio output

#### Scenario: Satellite returns to local listening

- **WHEN** Home Assistant finishes the conversation without requesting native continuation
- **THEN** the satellite SHALL return to local wake-word listening without requiring a manual restart

### Requirement: Home Assistant provides an Italian Assist pipeline

Home Assistant SHALL expose a configured Italian Assist pipeline that uses selectable Wyoming Azure STT/TTS services for Italian speech, Home Assistant's conversation/intent engine for ordinary command execution, and a selectable official Google Gemini conversation agent for the dedicated `pensa` AI path. The pipeline SHALL be selectable by the LVA satellite integration, and Azure STT/TTS SHALL remain the speech transport for the Gemini path.

#### Scenario: Spoken switch command completes end to end

- **WHEN** a user activates the satellite and says an Italian command to turn on the configured test switch without the `pensa` prefix
- **THEN** Home Assistant SHALL transcribe the utterance, execute the switch action, synthesize the response, and send audio back to the same satellite

#### Scenario: Spoken AI request completes end to end

- **WHEN** a user activates the satellite and says an Italian request beginning with `pensa`
- **THEN** Home Assistant SHALL send the request to the official Google Gemini conversation agent
- **AND** SHALL synthesize the response with the configured Azure TTS service
- **AND** SHALL send the spoken response back to the same satellite

#### Scenario: STT or TTS is unavailable

- **WHEN** either configured speech service or the selected conversation agent is unavailable
- **THEN** the pipeline SHALL fail the conversation with an observable error
- **AND** MUST NOT report the requested command or AI response as successfully completed
