package dev.dmgiangi.core.server.infrastructure.messaging.inbound;

import dev.dmgiangi.core.server.domain.model.*;
import dev.dmgiangi.core.server.domain.model.event.ActuatorFeedbackReceivedEvent;
import dev.dmgiangi.core.server.domain.model.event.ReportedStateChangedEvent;
import dev.dmgiangi.core.server.domain.port.DeviceStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActuatorFeedbackProcessor")
class ActuatorFeedbackProcessorTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller1", "component1");

    @Mock
    private DeviceStateRepository repository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ActuatorFeedbackProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ActuatorFeedbackProcessor(repository, eventPublisher);
    }

    @Nested
    @DisplayName("RELAY Parsing")
    class RelayParsingTests {

        @ParameterizedTest(name = "rawValue ''{0}'' should parse to state={1}")
        @CsvSource({
            "0, false",
            "1, true",
            "LOW, false",
            "HIGH, true",
            "low, false",
            "high, true",
            "Low, false",
            "High, true"
        })
        @DisplayName("should parse valid relay values")
        void shouldParseValidRelayValues(String rawValue, boolean expectedState) {
            final var feedback = createFeedback(DeviceType.RELAY, rawValue);
            final var event = new ActuatorFeedbackReceivedEvent(this, feedback);

            processor.onActuatorFeedbackReceived(event);

            final var stateCaptor = ArgumentCaptor.forClass(ReportedDeviceState.class);
            verify(repository).saveReportedState(stateCaptor.capture());

            final var savedState = stateCaptor.getValue();
            assertThat(savedState.id()).isEqualTo(DEVICE_ID);
            assertThat(savedState.type()).isEqualTo(DeviceType.RELAY);
            assertThat(savedState.value()).isEqualTo(new RelayValue(expectedState));
            assertThat(savedState.isKnown()).isTrue();
        }

        @ParameterizedTest(name = "rawValue ''{0}'' with whitespace should parse correctly")
        @CsvSource({
            "' 0 ', false",
            "' 1 ', true",
            "' LOW ', false",
            "' HIGH ', true"
        })
        @DisplayName("should trim whitespace from relay values")
        void shouldTrimWhitespaceFromRelayValues(String rawValue, boolean expectedState) {
            final var feedback = createFeedback(DeviceType.RELAY, rawValue);
            final var event = new ActuatorFeedbackReceivedEvent(this, feedback);

            processor.onActuatorFeedbackReceived(event);

            final var stateCaptor = ArgumentCaptor.forClass(ReportedDeviceState.class);
            verify(repository).saveReportedState(stateCaptor.capture());
            assertThat(stateCaptor.getValue().value()).isEqualTo(new RelayValue(expectedState));
        }

        @ParameterizedTest
        @ValueSource(strings = {"2", "ON", "OFF", "yes", "no", "invalid"})
        @DisplayName("should not save state for invalid relay values")
        void shouldNotSaveStateForInvalidRelayValues(String rawValue) {
            final var feedback = createFeedback(DeviceType.RELAY, rawValue);
            final var event = new ActuatorFeedbackReceivedEvent(this, feedback);

            processor.onActuatorFeedbackReceived(event);

            verify(repository, never()).saveReportedState(any());
            verify(eventPublisher, never()).publishEvent(any(ReportedStateChangedEvent.class));
        }
    }

    @Nested
    @DisplayName("FAN Parsing")
    class FanParsingTests {

        @ParameterizedTest(name = "rawValue ''{0}'' should parse to speed={0}")
        @ValueSource(ints = {0, 1, 2, 3, 4})
        @DisplayName("should parse valid fan values")
        void shouldParseValidFanValues(int speed) {
            final var feedback = createFeedback(DeviceType.FAN, String.valueOf(speed));
            final var event = new ActuatorFeedbackReceivedEvent(this, feedback);

            processor.onActuatorFeedbackReceived(event);

            final var stateCaptor = ArgumentCaptor.forClass(ReportedDeviceState.class);
            verify(repository).saveReportedState(stateCaptor.capture());

            final var savedState = stateCaptor.getValue();
            assertThat(savedState.id()).isEqualTo(DEVICE_ID);
            assertThat(savedState.type()).isEqualTo(DeviceType.FAN);
            assertThat(savedState.value()).isEqualTo(new FanValue(speed));
            assertThat(savedState.isKnown()).isTrue();
        }

        @Test
        @DisplayName("should trim whitespace from fan values")
        void shouldTrimWhitespaceFromFanValues() {
            final var feedback = createFeedback(DeviceType.FAN, " 3 ");
            final var event = new ActuatorFeedbackReceivedEvent(this, feedback);

            processor.onActuatorFeedbackReceived(event);

            final var stateCaptor = ArgumentCaptor.forClass(ReportedDeviceState.class);
            verify(repository).saveReportedState(stateCaptor.capture());
            assertThat(stateCaptor.getValue().value()).isEqualTo(new FanValue(3));
        }
    }

    private ActuatorFeedback createFeedback(DeviceType type, String rawValue) {
        return new ActuatorFeedback(DEVICE_ID, type, rawValue, Instant.now());
    }
}

