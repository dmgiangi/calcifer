package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UserIntent")
class UserIntentTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller-1", "relay-1");
    private static final Instant TIMESTAMP = Instant.parse("2026-01-25T10:00:00Z");

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should reject null device id")
        void shouldRejectNullDeviceId() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new UserIntent(null, DeviceType.RELAY, new RelayValue(true), TIMESTAMP)
            );
            assertEquals("Device id must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null device type")
        void shouldRejectNullDeviceType() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new UserIntent(DEVICE_ID, null, new RelayValue(true), TIMESTAMP)
            );
            assertEquals("Device type must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null value")
        void shouldRejectNullValue() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.RELAY, null, TIMESTAMP)
            );
            assertEquals("Value must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null requestedAt")
        void shouldRejectNullRequestedAt() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), null)
            );
            assertEquals("RequestedAt must not be null", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Type-Value Consistency")
    class TypeValueConsistencyTests {

        @Test
        @DisplayName("RELAY should accept RelayValue")
        void relayShouldAcceptRelayValue() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(true), TIMESTAMP);
            assertEquals(new RelayValue(true), intent.value());
        }

        @Test
        @DisplayName("RELAY should reject non-RelayValue")
        void relayShouldRejectNonRelayValue() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.RELAY, new FanValue(128), TIMESTAMP)
            );
            assertEquals("Relay value must be RelayValue", exception.getMessage());
        }

        @Test
        @DisplayName("FAN should accept FanValue")
        void fanShouldAcceptFanValue() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(128), TIMESTAMP);
            assertEquals(new FanValue(128), intent.value());
        }

        @Test
        @DisplayName("FAN should reject non-FanValue")
        void fanShouldRejectNonFanValue() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.FAN, new RelayValue(true), TIMESTAMP)
            );
            assertEquals("Fan value must be FanValue", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Factory Method: now()")
    class FactoryMethodTests {

        @Test
        @DisplayName("should create UserIntent with current timestamp")
        void shouldCreateWithCurrentTimestamp() {
            final var before = Instant.now();
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var after = Instant.now();

            assertNotNull(intent.requestedAt());
            assertTrue(intent.requestedAt().compareTo(before) >= 0);
            assertTrue(intent.requestedAt().compareTo(after) <= 0);
        }

        @Test
        @DisplayName("should preserve all other fields")
        void shouldPreserveAllFields() {
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.FAN, new FanValue(255));

            assertEquals(DEVICE_ID, intent.id());
            assertEquals(DeviceType.FAN, intent.type());
            assertEquals(new FanValue(255), intent.value());
        }
    }

    @Nested
    @DisplayName("Record Accessors")
    class RecordAccessorTests {

        @Test
        @DisplayName("should return all fields correctly")
        void shouldReturnAllFields() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, new RelayValue(false), TIMESTAMP);

            assertEquals(DEVICE_ID, intent.id());
            assertEquals(DeviceType.RELAY, intent.type());
            assertEquals(new RelayValue(false), intent.value());
            assertEquals(TIMESTAMP, intent.requestedAt());
        }
    }
}

