package dev.dmgiangi.core.server.infrastructure.rest.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Custom constraint for validating IntentRequest type-value consistency.
 * Per Phase 0.12: RELAY→Boolean, FAN→Integer 0-4.
 */
@Documented
@Constraint(validatedBy = IntentRequestValidator.class)
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIntentRequest {

    String message() default "Invalid intent request: type and value are inconsistent";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

