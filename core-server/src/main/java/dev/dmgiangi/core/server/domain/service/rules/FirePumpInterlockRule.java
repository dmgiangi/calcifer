package dev.dmgiangi.core.server.domain.service.rules;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Accepted;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Modified;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Critical safety rule: Fire ON → Pump must be ON.
 *
 * <p>In a Termocamino (wood-burning stove with water heating) system:
 * <ul>
 *   <li>When fire is ON, the pump MUST be ON to circulate water</li>
 *   <li>Without water circulation, the system could overheat</li>
 *   <li>This rule auto-enables the pump when fire is turned ON</li>
 * </ul>
 *
 * <p>This is the inverse of PumpFireInterlockRule:
 * <ul>
 *   <li>PumpFireInterlockRule: Prevents fire OFF when pump ON</li>
 *   <li>FirePumpInterlockRule: Forces pump ON when fire ON</li>
 * </ul>
 */
@Component
public class FirePumpInterlockRule extends AbstractHardcodedSafetyRule {

    /**
     * Component ID pattern for pump relay.
     */
    private static final String PUMP_COMPONENT_PATTERN = "(?i).*pump.*";

    /**
     * Component ID pattern for fire control relay.
     */
    private static final String FIRE_COMPONENT_PATTERN = "(?i)fire.*";

    public FirePumpInterlockRule() {
        super(
                "FIRE_PUMP_INTERLOCK",
                "Fire-Pump Interlock Safety Rule",
                "Ensures pump is ON when fire is ON to prevent overheating",
                10 // High priority within HARDCODED_SAFETY
        );
    }

    @Override
    public boolean appliesTo(SafetyContext context) {
        // Only applies to RELAY devices that control pump
        if (context.deviceType() != DeviceType.RELAY) {
            return false;
        }

        // Only applies to pump relays
        final var componentId = context.deviceId().componentId();
        return componentId.matches(PUMP_COMPONENT_PATTERN);
    }

    @Override
    public ValidationResult evaluate(SafetyContext context) {
        // Only check when trying to turn pump OFF
        if (!(context.proposedValue() instanceof RelayValue relayValue) || relayValue.state()) {
            return Accepted.of(getId());
        }

        // Find fire relay in related devices
        final var fireState = findFireState(context);
        if (fireState.isEmpty()) {
            // No fire relay found in context - cannot verify interlock, allow change
            return Accepted.of(getId());
        }

        // Check if fire is ON
        final var fireValue = fireState.get();
        if (fireValue.state()) {
            // Fire is ON - pump must stay ON
            // Instead of refusing, we modify to keep pump ON
            return Modified.of(
                    getId(),
                    relayValue,
                    new RelayValue(true),
                    "Pump must remain ON while fire is active. " +
                            "Turn off fire first before disabling pump."
            );
        }

        return Accepted.of(getId());
    }

    @Override
    public Optional<Object> suggestCorrection(SafetyContext context) {
        // Suggest keeping pump ON
        return Optional.of(new RelayValue(true));
    }

    private Optional<RelayValue> findFireState(SafetyContext context) {
        // Search in related device states for fire relay
        for (final var entry : context.relatedDeviceStates().entrySet()) {
            final var deviceId = entry.getKey();
            final var snapshot = entry.getValue();

            if (isFireDevice(deviceId) && snapshot.desired() != null) {
                if (snapshot.desired().value() instanceof RelayValue relayValue) {
                    return Optional.of(relayValue);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isFireDevice(DeviceId deviceId) {
        return deviceId.componentId().matches(FIRE_COMPONENT_PATTERN);
    }
}

