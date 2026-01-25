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
                new UserIntent(null, DeviceType.RELAY, true, TIMESTAMP)
            );
            assertEquals("Device id must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null device type")
        void shouldRejectNullDeviceType() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new UserIntent(DEVICE_ID, null, true, TIMESTAMP)
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
                new UserIntent(DEVICE_ID, DeviceType.RELAY, true, null)
            );
            assertEquals("RequestedAt must not be null", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Type-Value Consistency")
    class TypeValueConsistencyTests {

        @Test
        @DisplayName("RELAY should accept Boolean value")
        void relayShouldAcceptBoolean() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, true, TIMESTAMP);
            assertEquals(true, intent.value());
        }

        @Test
        @DisplayName("RELAY should reject non-Boolean value")
        void relayShouldRejectNonBoolean() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.RELAY, "invalid", TIMESTAMP)
            );
            assertEquals("Relay value must be Boolean", exception.getMessage());
        }

        @Test
        @DisplayName("STEP_RELAY should accept StepRelayState value")
        void stepRelayShouldAcceptStepRelayState() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.STEP_RELAY, StepRelayState.LEVEL_2, TIMESTAMP);
            assertEquals(StepRelayState.LEVEL_2, intent.value());
        }

        @Test
        @DisplayName("STEP_RELAY should reject non-StepRelayState value")
        void stepRelayShouldRejectNonStepRelayState() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new UserIntent(DEVICE_ID, DeviceType.STEP_RELAY, true, TIMESTAMP)
            );
            assertEquals("Step Relay value must be StepRelayState", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Factory Method: now()")
    class FactoryMethodTests {

        @Test
        @DisplayName("should create UserIntent with current timestamp")
        void shouldCreateWithCurrentTimestamp() {
            final var before = Instant.now();
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.RELAY, true);
            final var after = Instant.now();

            assertNotNull(intent.requestedAt());
            assertTrue(intent.requestedAt().compareTo(before) >= 0);
            assertTrue(intent.requestedAt().compareTo(after) <= 0);
        }

        @Test
        @DisplayName("should preserve all other fields")
        void shouldPreserveAllFields() {
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.STEP_RELAY, StepRelayState.FULL_POWER);

            assertEquals(DEVICE_ID, intent.id());
            assertEquals(DeviceType.STEP_RELAY, intent.type());
            assertEquals(StepRelayState.FULL_POWER, intent.value());
        }
    }

    @Nested
    @DisplayName("Record Accessors")
    class RecordAccessorTests {

        @Test
        @DisplayName("should return all fields correctly")
        void shouldReturnAllFields() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.RELAY, false, TIMESTAMP);

            assertEquals(DEVICE_ID, intent.id());
            assertEquals(DeviceType.RELAY, intent.type());
            assertEquals(false, intent.value());
            assertEquals(TIMESTAMP, intent.requestedAt());
        }
    }
}

