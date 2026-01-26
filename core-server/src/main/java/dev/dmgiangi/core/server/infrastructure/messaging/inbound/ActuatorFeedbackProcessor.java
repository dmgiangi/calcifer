package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.model.ActuatorFeedback;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.domain.model.DeviceValue;
import dev.dmgiangi.core.server.domain.model.FanValue;
import dev.dmgiangi.core.server.domain.model.RelayValue;
import dev.dmgiangi.core.server.domain.model.ReportedDeviceState;
import dev.dmgiangi.core.server.domain.model.event.ActuatorFeedbackReceivedEvent;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Processes raw actuator feedback received from MQTT and converts it to ReportedDeviceState.
 *
 * <p>This component:
 * <ul>
 *   <li>Listens for {@link ActuatorFeedbackReceivedEvent}</li>
 *   <li>Parses the raw payload string into the appropriate {@link DeviceValue}</li>
 *   <li>Creates and saves a {@link ReportedDeviceState}</li>
 *   <li>Publishes a {@link ReportedStateChangedEvent} to trigger downstream processing</li>
 * </ul>
 *
 * <p>Payload parsing rules:
 * <ul>
 *   <li>RELAY: "0", "LOW" → false; "1", "HIGH" → true</li>
 *   <li>FAN: "0"-"255" → integer speed value</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActuatorFeedbackProcessor {

    private final DeviceStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Handles actuator feedback events by parsing the raw value,
     * persisting the reported state, and publishing a state change event.
     *
     * @param event the actuator feedback received event
     */
    @EventListener
    public void onActuatorFeedbackReceived(ActuatorFeedbackReceivedEvent event) {
        final var feedback = event.getFeedback();
        log.debug("Processing actuator feedback for device {}: type={}, rawValue={}",
                feedback.id(), feedback.type(), feedback.rawValue());

        try {
            final var deviceValue = parseRawValue(feedback.type(), feedback.rawValue());
            final var reportedState = ReportedDeviceState.known(
                    feedback.id(),
                    feedback.type(),
                    deviceValue
            );

            repository.saveReportedState(reportedState);
            log.debug("Saved reported state for device {}: {}", feedback.id(), deviceValue);

            eventPublisher.publishEvent(new ReportedStateChangedEvent(this, reportedState));
            log.debug("Published ReportedStateChangedEvent for device {}", feedback.id());

        } catch (IllegalArgumentException e) {
            log.error("Failed to parse actuator feedback for device {}: {}",
                    feedback.id(), e.getMessage());
        }
    }

    /**
     * Parses the raw MQTT payload into the appropriate DeviceValue.
     *
     * @param type     the device type
     * @param rawValue the raw payload string
     * @return the parsed DeviceValue
     * @throws IllegalArgumentException if the raw value cannot be parsed
     */
    private DeviceValue parseRawValue(DeviceType type, String rawValue) {
        return switch (type) {
            case RELAY -> parseRelayValue(rawValue);
            case FAN -> parseFanValue(rawValue);
            case TEMPERATURE_SENSOR -> throw new IllegalArgumentException(
                    "TEMPERATURE_SENSOR is an INPUT device and cannot receive actuator feedback");
        };
    }

    /**
     * Parses relay feedback payload.
     * Valid values: "0", "1", "HIGH", "LOW" (case-insensitive)
     *
     * @param rawValue the raw payload
     * @return RelayValue with the parsed state
     * @throws IllegalArgumentException if the value is invalid
     */
    private RelayValue parseRelayValue(String rawValue) {
        final var normalized = rawValue.trim().toUpperCase();
        return switch (normalized) {
            case "0", "LOW" -> new RelayValue(false);
            case "1", "HIGH" -> new RelayValue(true);
            default -> throw new IllegalArgumentException(
                    "Invalid relay value: '" + rawValue + "'. Expected: 0, 1, HIGH, or LOW");
        };
    }

    /**
     * Parses fan feedback payload.
     * Valid values: integer between 0 and 255
     *
     * @param rawValue the raw payload
     * @return FanValue with the parsed speed
     * @throws IllegalArgumentException if the value is invalid
     */
    private FanValue parseFanValue(String rawValue) {
        try {
            final var speed = Integer.parseInt(rawValue.trim());
            return new FanValue(speed); // FanValue constructor validates 0-255 range
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid fan value: '" + rawValue + "'. Expected: integer 0-255");
        }
    }
}

