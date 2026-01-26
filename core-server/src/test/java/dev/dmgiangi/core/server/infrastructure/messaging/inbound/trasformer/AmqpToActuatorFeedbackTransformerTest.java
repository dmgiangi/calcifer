package dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer;

import dev.dmgiangi.core.server.domain.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AmqpToActuatorFeedbackTransformer")
class AmqpToActuatorFeedbackTransformerTest {

    private AmqpToActuatorFeedbackTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new AmqpToActuatorFeedbackTransformer();
    }

    @Nested
    @DisplayName("FAN feedback parsing")
    class FanFeedbackTests {

        @Test
        @DisplayName("should parse FAN routing key and payload correctly")
        void shouldParseFanRoutingKeyAndPayload() {
            final var message = createMessage(".client1.fan.fan1.state", "128");

            final var result = transformer.transform(message);

            assertThat(result.id().controllerId()).isEqualTo("client1");
            assertThat(result.id().componentId()).isEqualTo("fan1");
            assertThat(result.type()).isEqualTo(DeviceType.FAN);
            assertThat(result.rawValue()).isEqualTo("128");
            assertThat(result.receivedAt()).isNotNull();
        }

        @Test
        @DisplayName("should handle FAN value 0")
        void shouldHandleFanValueZero() {
            final var message = createMessage(".controller2.fan.pwm_fan.state", "0");

            final var result = transformer.transform(message);

            assertThat(result.id().controllerId()).isEqualTo("controller2");
            assertThat(result.id().componentId()).isEqualTo("pwm_fan");
            assertThat(result.type()).isEqualTo(DeviceType.FAN);
            assertThat(result.rawValue()).isEqualTo("0");
        }
    }

    @Nested
    @DisplayName("RELAY feedback parsing")
    class RelayFeedbackTests {

        @Test
        @DisplayName("should parse RELAY routing key with digital_output handler")
        void shouldParseRelayRoutingKey() {
            final var message = createMessage(".client1.digital_output.relay1.state", "1");

            final var result = transformer.transform(message);

            assertThat(result.id().controllerId()).isEqualTo("client1");
            assertThat(result.id().componentId()).isEqualTo("relay1");
            assertThat(result.type()).isEqualTo(DeviceType.RELAY);
            assertThat(result.rawValue()).isEqualTo("1");
        }

        @Test
        @DisplayName("should parse RELAY with HIGH value")
        void shouldParseRelayWithHighValue() {
            final var message = createMessage(".esp32_01.digital_output.light.state", "HIGH");

            final var result = transformer.transform(message);

            assertThat(result.id().controllerId()).isEqualTo("esp32_01");
            assertThat(result.id().componentId()).isEqualTo("light");
            assertThat(result.type()).isEqualTo(DeviceType.RELAY);
            assertThat(result.rawValue()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("should parse RELAY with LOW value")
        void shouldParseRelayWithLowValue() {
            final var message = createMessage(".esp32_01.digital_output.pump.state", "LOW");

            final var result = transformer.transform(message);

            assertThat(result.rawValue()).isEqualTo("LOW");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw exception when routing key header is missing")
        void shouldThrowWhenRoutingKeyMissing() {
            final var message = MessageBuilder.withPayload("128".getBytes()).build();

            assertThatThrownBy(() -> transformer.transform(message))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing amqp_receivedRoutingKey header");
        }

        @Test
        @DisplayName("should throw exception for unknown handler type")
        void shouldThrowForUnknownHandlerType() {
            final var message = createMessage(".client1.unknown_handler.device1.state", "100");

            assertThatThrownBy(() -> transformer.transform(message))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown handler type: unknown_handler");
        }
    }

    private Message<byte[]> createMessage(String routingKey, String payload) {
        return MessageBuilder
                .withPayload(payload.getBytes())
                .setHeader("amqp_receivedRoutingKey", routingKey)
                .build();
    }
}

