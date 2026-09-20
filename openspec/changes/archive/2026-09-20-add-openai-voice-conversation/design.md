# Design

## Context

The existing `calcifer-home` deployment provides Home Assistant with an Italian Assist pipeline backed by Wyoming Azure STT/TTS and a Linux satellite. Home Assistant is already responsible for conversation routing, while the satellite supports native Assist start/continue conversation behavior. See `proposal.md` and the spec deltas for the user-visible contract.

## Goals / Non-Goals

**Goals:**

- Add the official Google Gemini integration without replacing Azure speech services.
- Keep ordinary home-control commands on the existing local Assist path.
- Use Home Assistant's native `conversation_id` and `continue_conversation` behavior rather than implementing a custom idle timer or session controller.
- Make the API-key setup safe and explicit.
- Keep AI access limited to entities exposed to Assist.

**Non-Goals:**

- No Gemini Live migration; the selected integration uses the normal Home Assistant conversation-agent flow.
- No custom Home Assistant integration, external conversation proxy, or new Kubernetes workload.
- No forced 30-second timeout; native continuation ends when the conversation agent does not request another turn.
- No automatic injection of a Gemini key into Home Assistant's `.storage` files.

## Decisions

### Use the official Google Gemini integration

The integration is configured in Home Assistant's UI and stores its credential in a Home Assistant config entry. This matches the supported lifecycle and avoids treating a Kubernetes Secret or `secrets.yaml` entry as if it could configure a UI-managed integration. A Google AI consumer subscription and Gemini API billing are separate concerns.

**Alternative considered:** mounting a Gemini API key as a Kubernetes Secret or adding it to the existing SOPS secret. Rejected because the official integration stores and uses its credential through the Home Assistant config-entry flow, so repository-managed secret material would be unnecessary.

### Keep Azure STT/TTS

The Gemini agent receives transcribed text from the existing Italian pipeline and returns response text to the existing Azure TTS service. This preserves the current audio path and avoids sending raw microphone audio to a second provider.

**Alternative considered:** Gemini's native Live/audio path. Rejected for this change because it replaces STT/TTS with native Gemini audio and is a separate architectural choice.

### Use native continuation only

The agent's `continue_conversation` result controls whether the satellite reopens listening. The conversation context is carried by Home Assistant's conversation identifier. No automation, timer, `conversation.process` loop, or custom stop controller is added.

**Alternative considered:** forcing every response to end with a question or implementing a 30-second controller. Rejected because it is less natural and outside the selected requirement.

### Separate AI requests from ordinary commands

The `pensa` entry phrase is documented as the explicit user choice for AI conversation. The Assist pipeline uses Home Assistant's local-first handling so recognized ordinary commands are completed locally; requests beginning with `pensa` fall through to the Google Gemini conversation agent. The implementation must not silently route recognized ordinary Assist requests to Gemini.

**Alternative considered:** two independent agents selected by a prefix router. Rejected because the official Google Gemini integration does not provide a native per-utterance prefix router, and adding one would require a custom conversation controller outside the selected scope.

## Risks / Trade-offs

- **[Risk]** Native continuation depends on the conversation agent returning `continue_conversation=true` when a follow-up is appropriate. **Mitigation:** document and test follow-up wording, and keep the normal wake-word path available when continuation ends.
- **[Risk]** Gemini API usage incurs provider billing and sends the selected transcript/context outside the cluster. **Mitigation:** document billing/privacy, expose no Assist entities for the initial conversation-only setup, and keep the explicit `pensa` entry point.
- **[Risk]** Home Assistant UI-managed config entries are not fully declarative in this repository. **Mitigation:** document the one-time UI setup and verify the config entry after deployment; do not duplicate the key in Kubernetes manifests.
- **[Risk]** Provider or integration errors can interrupt a voice interaction. **Mitigation:** require observable failure behavior and preserve the existing local pipeline for ordinary commands.

## Migration Plan

1. Deploy the repository changes without adding a new secret or changing the Azure speech deployments.
2. In Home Assistant, configure the official Google Gemini integration with the user's API key and select the intended model or recommended settings.
3. Create or update the Italian Assist pipeline so Azure Wyoming STT/TTS remain selected and the Gemini agent is available for the dedicated AI path.
4. Expose only the intended entities to Assist and prefer conversation-only operation initially.
5. Test one `pensa` request, one follow-up without the wake word, one ordinary local command, and one unavailable-provider failure.
6. Roll back by disabling/removing the Gemini config entry and reverting the pipeline selection; existing Azure speech services and local commands remain available.
