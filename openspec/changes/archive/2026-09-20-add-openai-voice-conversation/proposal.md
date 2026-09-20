# Proposal

## Why

The existing Italian voice pipeline handles Home Assistant commands locally, but it does not provide a dedicated way to start a spoken conversation with a general-purpose AI. Home Assistant's official Google Gemini integration and native continued-conversation support add this capability while preserving the existing Azure STT/TTS services and the Linux satellite.

## What Changes

- Add a Google Gemini-backed Home Assistant conversation agent configured through the official integration.
- Add a dedicated voice entry phrase using `pensa` so ordinary home-control requests remain on the local Assist path.
- Preserve the same conversation context across native follow-up turns until the assistant indicates that continuation is no longer expected.
- Configure the Italian Assist pipeline to use Azure Wyoming STT and TTS with the Google Gemini conversation agent for the dedicated AI path.
- Document that the Gemini API key is configured in Home Assistant's integration UI and is not committed to Kubernetes manifests, SOPS files, or repository configuration.
- Document verification, permissions, failure behavior, and rollback for the AI conversation path.

## Capabilities

### New Capabilities

- `home-ai-voice-conversation`: Dedicated spoken conversations with the official Google Gemini integration, native follow-up turns, and secret-handling requirements.

### Modified Capabilities

- `home-voice-assistant`: Extend the existing Italian voice pipeline with an explicit AI conversation path while retaining Azure speech services and local handling for ordinary commands.

## Impact

- Home Assistant configuration and Assist pipeline setup in `calcifer-home`.
- The Linux voice satellite's interaction with Home Assistant; no new satellite credentials or cloud egress proxy are required.
- Gemini API usage and billing for the personal API key configured in Home Assistant. A Google AI consumer subscription does not automatically provide unlimited Gemini API usage.
- Existing Azure STT/TTS deployments remain unchanged.
- No new external repository dependency is required; the official Google Gemini integration is provided by Home Assistant.
