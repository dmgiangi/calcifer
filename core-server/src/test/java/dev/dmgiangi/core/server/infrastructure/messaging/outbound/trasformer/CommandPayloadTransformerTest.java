package dev.dmgiangi.core.server.infrastructure.messaging.outbound.trasformer;

import dev.dmgiangi.core.server.domain.model.DeviceId;
import dev.dmgiangi.core.server.domain.model.DeviceType;
import dev.dmgiangi.core.server.infrastructure.messaging.outbound.event.DeviceCommandEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommandPayloadTransformer")
class CommandPayloadTransformerTest {

    private CommandPayloadTransformer transformer;

    @BeforeEach
    void setUp() {
        final var objectMapper = new ObjectMapper();
        transformer = new CommandPayloadTransformer(objectMapper);
    }

    @Nested
    @DisplayName("Relay Command Tests")
    class RelayCommandTests {

        @Test
        @DisplayName("should create relay ON command with state '1'")
        void shouldCreateRelayOnCommand_withStateOne() {
            final var deviceId = new DeviceId("controller-01", "relay-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.RELAY, true);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getPayload()).contains("\"state\":\"1\"");
        }

        @Test
        @DisplayName("should create relay OFF command with state '0'")
        void shouldCreateRelayOffCommand_withStateZero() {
            final var deviceId = new DeviceId("controller-01", "relay-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.RELAY, false);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getPayload()).contains("\"state\":\"0\"");
        }

        @Test
        @DisplayName("should set correct routing key for relay command")
        void shouldSetCorrectRoutingKeyForRelay() {
            final var deviceId = new DeviceId("controller-01", "relay-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.RELAY, true);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getHeaders().get("mqtt_routing_key"))
                    .isEqualTo(".controller-01.digital_output.relay-01.set");
        }
    }

    @Nested
    @DisplayName("Fan Command Tests")
    class FanCommandTests {

        @Test
        @DisplayName("should create fan command with speed 0 (off)")
        void shouldCreateFanCommand_withSpeedZero() {
            final var deviceId = new DeviceId("controller-01", "fan-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.FAN, 0);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getPayload()).isEqualTo("0");
        }

        @Test
        @DisplayName("should create fan command with maximum speed 4")
        void shouldCreateFanCommand_withMaxSpeed() {
            final var deviceId = new DeviceId("controller-01", "fan-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.FAN, 4);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getPayload()).isEqualTo("4");
        }

        @ParameterizedTest(name = "speed={0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("should create fan command with various speeds")
        void shouldCreateFanCommand_withVariousSpeeds(int speed) {
            final var deviceId = new DeviceId("controller-01", "fan-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.FAN, speed);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getPayload()).isEqualTo(String.valueOf(speed));
        }

        @Test
        @DisplayName("should set correct routing key for fan command")
        void shouldSetCorrectRoutingKeyForFan() {
            final var deviceId = new DeviceId("controller-01", "fan-01");
            final var event = new DeviceCommandEvent(deviceId, DeviceType.FAN, 3);

            final var result = transformer.transform(event);

            assertThat(result).hasSize(1);
            final var message = result.getFirst();
            assertThat(message.getHeaders().get("mqtt_routing_key"))
                    .isEqualTo(".controller-01.fan.fan-01.set");
        }
    }
}

