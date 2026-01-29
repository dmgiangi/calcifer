package dev.dmgiangi.core.server.infrastructure.rest.validation;

import dev.dmgiangi.core.server.infrastructure.rest.dto.IntentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator for IntentRequest type-value consistency.
 * Per Phase 0.12: RELAY→Boolean, FAN→Integer 0-4, TEMPERATURE_SENSOR→not allowed.
 */
public class IntentRequestValidator implements ConstraintValidator<ValidIntentRequest, IntentRequest> {

    @Override
    public boolean isValid(final IntentRequest request, final ConstraintValidatorContext context) {
        // Null checks are handled by @NotNull on fields
        if (request == null || request.type() == null || request.value() == null) {
            return true;
        }

        context.disableDefaultConstraintViolation();

        return switch (request.type()) {
            case RELAY -> validateRelayValue(request.value(), context);
            case FAN -> validateFanValue(request.value(), context);
            case TEMPERATURE_SENSOR -> {
                context.buildConstraintViolationWithTemplate(
                        "Cannot set intent for input device type: TEMPERATURE_SENSOR"
                ).addConstraintViolation();
                yield false;
            }
        };
    }

    private boolean validateRelayValue(final Object value, final ConstraintValidatorContext context) {
        // Accept Boolean directly or values that can be converted to Boolean
        if (value instanceof Boolean) {
            return true;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                return true;
            }
        }
        if (value instanceof Number n) {
            final var intVal = n.intValue();
            if (intVal == 0 || intVal == 1) {
                return true;
            }
        }

        context.buildConstraintViolationWithTemplate(
                "RELAY value must be a boolean (true/false) or convertible to boolean (0/1)"
        ).addConstraintViolation();
        return false;
    }

    private boolean validateFanValue(final Object value, final ConstraintValidatorContext context) {
        Integer intValue = null;

        if (value instanceof Number n) {
            intValue = n.intValue();
        } else if (value instanceof String s) {
            try {
                intValue = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                // Will fail validation below
            }
        }

        if (intValue == null) {
            context.buildConstraintViolationWithTemplate(
                    "FAN value must be an integer"
            ).addConstraintViolation();
            return false;
        }

        if (intValue < 0 || intValue > 4) {
            context.buildConstraintViolationWithTemplate(
                    "FAN speed must be between 0 and 4, got: " + intValue
            ).addConstraintViolation();
            return false;
        }

        return true;
    }
}

