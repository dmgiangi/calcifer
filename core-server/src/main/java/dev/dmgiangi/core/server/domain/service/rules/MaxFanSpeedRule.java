package dev.dmgiangi.core.server.domain.service.rules;

import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.safety.SafetyContext;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Accepted;
import dev.dmgiangi.core.server.domain.model.safety.ValidationResult.Modified;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Safety rule: Enforce maximum fan speed limit.
 *
 * <p>This rule ensures fan speed never exceeds the configured maximum.
 * Instead of refusing the request, it modifies the value to the maximum allowed.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Prevent motor damage from excessive speed</li>
 *   <li>Limit noise levels in residential settings</li>
 *   <li>Reduce power consumption</li>
 * </ul>
 */
@Component
public class MaxFanSpeedRule extends AbstractHardcodedSafetyRule {

    /**
     * Maximum allowed fan speed (0-4 range).
     * This is a hardcoded safety limit - configurable limits should use YAML rules.
     */
    private static final int MAX_SAFE_SPEED = 4;

    public MaxFanSpeedRule() {
        super(
                "MAX_FAN_SPEED",
                "Maximum Fan Speed Limit",
                "Enforces maximum fan speed of " + MAX_SAFE_SPEED + " for motor protection",
                50 // Medium priority within HARDCODED_SAFETY
        );
    }

    @Override
    public boolean appliesTo(SafetyContext context) {
        return context.deviceType() == DeviceType.FAN;
    }

    @Override
    public ValidationResult evaluate(SafetyContext context) {
        if (!(context.proposedValue() instanceof FanValue fanValue)) {
            // Not a FanValue - should not happen if appliesTo() is correct
            return Accepted.of(getId());
        }

        final var requestedSpeed = fanValue.speed();

        if (requestedSpeed <= MAX_SAFE_SPEED) {
            return Accepted.of(getId());
        }

        // Modify to maximum safe speed
        final var safeValue = new FanValue(MAX_SAFE_SPEED);
        return Modified.of(
                getId(),
                fanValue,
                safeValue,
                String.format("Requested speed %d exceeds maximum safe speed %d",
                        requestedSpeed, MAX_SAFE_SPEED)
        );
    }

    @Override
    public Optional<Object> suggestCorrection(SafetyContext context) {
        return Optional.of(new FanValue(MAX_SAFE_SPEED));
    }
}

