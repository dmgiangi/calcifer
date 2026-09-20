# Tasks

## 1. Repository documentation and contract

- [x] 1.1 Document the `pensa` local-first interaction, official Google Gemini setup, Azure STT/TTS preservation, and native continuation behavior in the Home Assistant operations guide; verify the instructions contain no credential value and target only `calcifer-home`.
- [x] 1.2 Document the Gemini API-key lifecycle, billing/privacy implications, Assist exposure restrictions, and rollback procedure; verify repository searches do not find a Gemini key or a new Kubernetes secret for this integration.

## 2. Home Assistant configuration procedure

- [x] 2.1 Define the exact Home Assistant UI setup for the official Google Gemini integration, including API billing distinction, recommended settings, and local-first handling; verify it is compatible with the deployed Home Assistant version `2026.9.2`.
- [x] 2.2 Define the Italian Assist pipeline setup using the existing Wyoming Azure STT/TTS entities and the Google Gemini conversation agent; verify the pipeline leaves ordinary recognized commands local and sends `pensa` requests to Gemini.
- [x] 2.3 Define the system prompt and test phrases for `pensa`, follow-up turns, conversation termination (including `smetti di pensare`), and refusal of unexposed entities; verify each expected spoken outcome manually from the satellite.

## 3. Validation

- [x] 3.1 Validate the OpenSpec change with `openspec validate add-openai-voice-conversation --type change --strict` and verify all artifacts are accepted.
- [x] 3.2 Run the repository's applicable manifest/configuration checks and verify the existing Azure speech deployments and Linux satellite configuration are unchanged.
- [x] 3.3 Perform an end-to-end Home Assistant validation: one local command, one `pensa` request, one follow-up without the wake word, normal conversation termination, and an unavailable/invalid-provider failure; verify no credentials appear in command output or logs.
