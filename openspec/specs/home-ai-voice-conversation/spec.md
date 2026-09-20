# home-ai-voice-conversation Specification

## Purpose

Fornisce una modalità vocale esplicita per conversare in italiano con l'agente
Google Gemini ufficiale di Home Assistant, mantenendo il contesto tra i turni e
separandola dai normali comandi domotici locali.

## Requirements

### Requirement: Dedicated AI conversation entry point

Home Assistant SHALL provide a documented dedicated voice entry point using the Italian phrase `pensa` after the configured wake word. Requests that do not use this entry point SHALL continue to use the normal local Assist handling path.

#### Scenario: AI conversation is selected

- **WHEN** the user activates the satellite and begins the request with `pensa`
- **THEN** Home Assistant SHALL route the request to the configured official Google Gemini conversation agent
- **AND** SHALL use the configured Italian speech pipeline for the spoken response

#### Scenario: Recognized ordinary command remains local

- **WHEN** the user activates the satellite and speaks an ordinary Home Assistant command without `pensa`
- **AND** Home Assistant recognizes the command through its local Assist handling
- **THEN** Home Assistant SHALL process the request through the normal local Assist path
- **AND** SHALL NOT send that request to the Google Gemini conversation agent

### Requirement: Native continued conversation

The AI conversation path SHALL preserve the conversation context across follow-up turns and SHALL allow the satellite to continue listening without requiring the wake word again when the conversation agent marks the response as requiring continuation.

#### Scenario: Follow-up turn keeps context

- **WHEN** the Google Gemini agent returns a response with native continued-conversation enabled
- **THEN** the satellite SHALL accept the next user utterance without a new wake word
- **AND** Home Assistant SHALL associate the utterance with the same conversation context

#### Scenario: Conversation ends normally

- **WHEN** the Google Gemini agent returns a response without native continued-conversation enabled
- **THEN** the satellite SHALL finish the current interaction
- **AND** SHALL return to wake-word listening

#### Scenario: User explicitly ends the AI conversation

- **WHEN** the user says `smetti di pensare`, `basta`, `fine conversazione`, or `torna in ascolto`
- **THEN** the Google Gemini agent SHALL provide a brief confirmation without asking another question
- **AND** SHALL return to wake-word listening by disabling native continuation

### Requirement: Gemini credential is secret-safe

The Gemini API key SHALL be configured through Home Assistant's official integration configuration and MUST NOT be committed in Kubernetes manifests, repository configuration, plaintext documentation, or SOPS-encrypted files created solely for this change.

#### Scenario: Credential is configured

- **WHEN** an administrator configures the official Google Gemini integration
- **THEN** Home Assistant SHALL store and use the API key through its config-entry mechanism
- **AND** the repository SHALL remain free of the key value

#### Scenario: Credential is absent or invalid

- **WHEN** the AI conversation is requested without a valid Gemini credential or with an exhausted/unavailable provider account
- **THEN** Home Assistant SHALL expose an observable conversation error
- **AND** SHALL NOT report a successful AI response

### Requirement: AI Home Assistant access is restricted

The Google Gemini conversation agent SHALL only receive Home Assistant entities and actions explicitly exposed to Assist, and the default AI conversation setup SHALL be documented as conversation-only unless the administrator deliberately enables Home Assistant control.

#### Scenario: Unexposed entity is requested

- **WHEN** the user asks the AI agent to inspect or control an entity that is not exposed to Assist
- **THEN** the agent SHALL not perform that operation
- **AND** SHALL communicate that the operation is unavailable