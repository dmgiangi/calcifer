package dev.dmgiangi.core.server.infrastructure.persistence;

import dev.dmgiangi.core.server.application.override.OverrideValidationPipeline;
import dev.dmgiangi.core.server.application.override.OverrideValidationPipeline.EffectiveOverride;
import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.port.OverrideResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Infrastructure adapter that implements {@link OverrideResolver} by delegating
 * to {@link OverrideValidationPipeline}.
 *
 * <p>This adapter bridges the domain layer (StateCalculator) with the application layer
 * (OverrideValidationPipeline) without creating circular dependencies.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverrideResolverAdapter implements OverrideResolver {

    private final OverrideValidationPipeline overrideValidationPipeline;

    @Override
    public Optional<ResolvedOverride> resolveEffective(final DeviceId deviceId, final String systemId) {
        final var deviceIdStr = deviceId.controllerId() + ":" + deviceId.componentId();

        log.trace("Resolving effective override for device {} (system: {})", deviceIdStr, systemId);

        return overrideValidationPipeline.resolveEffectiveForDevice(deviceIdStr, systemId)
                .flatMap(this::toResolvedOverride);
    }

    /**
     * Converts EffectiveOverride to ResolvedOverride.
     */
    private Optional<ResolvedOverride> toResolvedOverride(final EffectiveOverride effective) {
        final var value = effective.value();

        if (value == null) {
            log.warn("Override value is null for target {}", effective.targetId());
            return Optional.empty();
        }

        log.trace("Resolved override: category={}, value={}, isFromSystem={}",
                effective.category(), value, effective.isFromSystem());

        return Optional.of(ResolvedOverride.of(
                value,
                effective.category(),
                effective.reason(),
                effective.isFromSystem()
        ));
    }
}

