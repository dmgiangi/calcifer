package dev.dmgiangi.core.server.domain.service.rules;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Accepted;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Refused;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Critical safety rule: Pump ON → Fire OFF impossible.
 *
 * <p>In a Termocamino (wood-burning stove with water heating) system:
 * <ul>
 *   <li>The pump circulates water to dissipate heat</li>
 *   <li>If the pump is ON, the fire MUST remain controllable (cannot be forced OFF)</li>
 *   <li>This prevents thermal runaway if water circulation stops</li>
 * </ul>
 *
 * <p>This rule prevents turning OFF the fire relay when the pump is ON.
 * The inverse (pump OFF when fire ON) is handled by a separate rule.
 */
@Component
public class PumpFireInterlockRule extends AbstractHardcodedSafetyRule {

    /**
     * Component ID pattern for fire control relay.
     * Matches: fire, fire-relay, fire_relay, fireRelay, etc.
     */
    private static final String FIRE_COMPONENT_PATTERN = "(?i)fire.*";

    /**
     * Component ID pattern for pump relay.
     * Matches: pump, pump-relay, pump_relay, pumpRelay, circulation-pump, etc.
     */
    private static final String PUMP_COMPONENT_PATTERN = "(?i).*pump.*";

    public PumpFireInterlockRule() {
        super(
                "PUMP_FIRE_INTERLOCK",
                "Pump-Fire Interlock Safety Rule",
                "Prevents turning OFF fire when pump is ON to avoid thermal runaway",
                10 // High priority within HARDCODED_SAFETY
        );
    }

    @Override
    public boolean appliesTo(SafetyContext context) {
        // Only applies to RELAY devices that control fire
        if (context.deviceType() != DeviceType.RELAY) {
            return false;
        }

        // Only applies to fire control relays
        final var componentId = context.deviceId().componentId();
        return componentId.matches(FIRE_COMPONENT_PATTERN);
    }

    @Override
    public ValidationResult evaluate(SafetyContext context) {
        // Only check when trying to turn fire OFF
        if (!(context.proposedValue() instanceof RelayValue relayValue) || relayValue.state()) {
            return Accepted.of(getId());
        }

        // Find pump relay in related devices
        final var pumpState = findPumpState(context);
        if (pumpState.isEmpty()) {
            // No pump found in context - cannot verify interlock, allow change
            // This is safe because if pump exists, it should be in relatedDeviceStates
            return Accepted.of(getId());
        }

        // Check if pump is ON
        final var pumpValue = pumpState.get();
        if (pumpValue.state()) {
            return Refused.of(
                    getId(),
                    "Cannot turn OFF fire while pump is ON",
                    "Pump is currently ON. Turning off fire could cause thermal runaway. " +
                            "Turn off pump first or wait for system to cool down."
            );
        }

        return Accepted.of(getId());
    }

    @Override
    public Optional<Object> suggestCorrection(SafetyContext context) {
        // No safe correction - fire must stay ON while pump is running
        return Optional.empty();
    }

    private Optional<RelayValue> findPumpState(SafetyContext context) {
        // Search in related device states for pump relay
        for (final var entry : context.relatedDeviceStates().entrySet()) {
            final var deviceId = entry.getKey();
            final var snapshot = entry.getValue();

            if (isPumpDevice(deviceId) && snapshot.desired() != null) {
                if (snapshot.desired().value() instanceof RelayValue relayValue) {
                    return Optional.of(relayValue);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isPumpDevice(DeviceId deviceId) {
        return deviceId.componentId().matches(PUMP_COMPONENT_PATTERN);
    }
}

