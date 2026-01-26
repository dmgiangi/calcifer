package dev.dmgiangi.core.server.infrastructure.messaging.inbound.trasformer;

import dev.dmgiangi.core.server.domain.temperature.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AmqpToTemperatureTransformer")
class AmqpToTemperatureTransformerTest {

    private AmqpToTemperatureTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new AmqpToTemperatureTransformer();
    }

    @Nested
    @DisplayName("Temperature sensor parsing")
    class TemperatureSensorParsingTests {

        @Test
        @DisplayName("should parse DS18B20 sensor routing key and payload correctly")
        void shouldParseDs18b20SensorRoutingKeyAndPayload() {
            final var message = createMessage(".client1.ds18b20.sensor1.state", "23.5");

            final var result = transformer.transform(message);

            assertThat(result.client()).isEqualTo("client1");
            assertThat(result.type()).isEqualTo(SensorType.ds18b20);
            assertThat(result.sensorName()).isEqualTo("sensor1");
            assertThat(result.isError()).isFalse();
            assertThat(result.value()).isEqualTo(23.5);
        }

        @Test
        @DisplayName("should parse thermocouple sensor routing key and payload correctly")
        void shouldParseThermocoupleSensorRoutingKeyAndPayload() {
            final var message = createMessage(".esp32_01.thermocouple.probe1.state", "150.75");

            final var result = transformer.transform(message);

            assertThat(result.client()).isEqualTo("esp32_01");
            assertThat(result.type()).isEqualTo(SensorType.thermocouple);
            assertThat(result.sensorName()).isEqualTo("probe1");
            assertThat(result.isError()).isFalse();
            assertThat(result.value()).isEqualTo(150.75);
        }

        @Test
        @DisplayName("should handle case-insensitive sensor type")
        void shouldHandleCaseInsensitiveSensorType() {
            final var message = createMessage(".client1.DS18B20.sensor1.state", "20.0");

            final var result = transformer.transform(message);

            assertThat(result.type()).isEqualTo(SensorType.ds18b20);
        }
    }

    @Nested
    @DisplayName("Payload parsing")
    class PayloadParsingTests {

        @Test
        @DisplayName("should parse positive temperature value")
        void shouldParsePositiveTemperatureValue() {
            final var message = createMessage(".client1.ds18b20.sensor1.state", "100.0");

            final var result = transformer.transform(message);

            assertThat(result.isError()).isFalse();
            assertThat(result.value()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("should parse negative temperature value")
        void shouldParseNegativeTemperatureValue() {
            final var message = createMessage(".client1.ds18b20.freezer.state", "-18.5");

            final var result = transformer.transform(message);

            assertThat(result.isError()).isFalse();
            assertThat(result.value()).isEqualTo(-18.5);
        }

        @Test
        @DisplayName("should parse zero temperature value")
        void shouldParseZeroTemperatureValue() {
            final var message = createMessage(".client1.ds18b20.sensor1.state", "0");

            final var result = transformer.transform(message);

            assertThat(result.isError()).isFalse();
            assertThat(result.value()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should throw exception when routing key header is missing")
        void shouldThrowWhenRoutingKeyMissing() {
            final var message = MessageBuilder.withPayload("23.5".getBytes()).build();

            assertThatThrownBy(() -> transformer.transform(message))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Missing amqp_receivedRoutingKey header");
        }

        @Test
        @DisplayName("should return error when payload is not a valid number")
        void shouldReturnErrorWhenPayloadInvalid() {
            final var message = createMessage(".client1.ds18b20.sensor1.state", "invalid");

            final var result = transformer.transform(message);

            assertThat(result.isError()).isTrue();
            assertThat(result.value()).isNaN();
        }

        @Test
        @DisplayName("should throw exception for unknown sensor type")
        void shouldThrowForUnknownSensorType() {
            final var message = createMessage(".client1.unknown_sensor.sensor1.state", "23.5");

            assertThatThrownBy(() -> transformer.transform(message))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Message<byte[]> createMessage(String routingKey, String payload) {
        return MessageBuilder
                .withPayload(payload.getBytes())
                .setHeader("amqp_receivedRoutingKey", routingKey)
                .build();
    }
}

