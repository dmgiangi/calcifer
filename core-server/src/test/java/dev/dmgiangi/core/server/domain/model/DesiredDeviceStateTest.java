package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DesiredDeviceState}.
 * Tests type-value consistency validation and record semantics.
 */
@DisplayName("DesiredDeviceState")
class DesiredDeviceStateTest {

    private static final DeviceId TEST_DEVICE_ID = new DeviceId("test-controller", "test-component");
    private static final RelayValue RELAY_VALUE_ON = new RelayValue(true);
    private static final RelayValue RELAY_VALUE_OFF = new RelayValue(false);
    private static final FanValue FAN_VALUE_ZERO = new FanValue(0);
    private static final FanValue FAN_VALUE_MAX = new FanValue(255);
    private static final FanValue FAN_VALUE_MID = new FanValue(128);

    @Nested
    @DisplayName("Type-Value Consistency Validation")
    class TypeValueConsistencyTests {

        @Test
        @DisplayName("should accept RELAY type with RelayValue (on)")
        void shouldAcceptRelayTypeWithRelayValueOn() {
            final var state = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);

            assertAll(
                    () -> assertEquals(TEST_DEVICE_ID, state.id()),
                    () -> assertEquals(DeviceType.RELAY, state.type()),
                    () -> assertEquals(RELAY_VALUE_ON, state.value())
            );
        }

        @Test
        @DisplayName("should accept RELAY type with RelayValue (off)")
        void shouldAcceptRelayTypeWithRelayValueOff() {
            final var state = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_OFF);

            assertEquals(RELAY_VALUE_OFF, state.value());
        }

        @Test
        @DisplayName("should reject RELAY type with FanValue")
        void shouldRejectRelayTypeWithFanValue() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, FAN_VALUE_MID)
            );

            assertEquals("Relay value must be RelayValue", exception.getMessage());
        }

        @Test
        @DisplayName("should accept FAN type with FanValue (zero)")
        void shouldAcceptFanTypeWithFanValueZero() {
            final var state = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.FAN, FAN_VALUE_ZERO);

            assertAll(
                    () -> assertEquals(TEST_DEVICE_ID, state.id()),
                    () -> assertEquals(DeviceType.FAN, state.type()),
                    () -> assertEquals(FAN_VALUE_ZERO, state.value())
            );
        }

        @Test
        @DisplayName("should accept FAN type with FanValue (max)")
        void shouldAcceptFanTypeWithFanValueMax() {
            final var state = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.FAN, FAN_VALUE_MAX);

            assertEquals(FAN_VALUE_MAX, state.value());
        }

        @Test
        @DisplayName("should reject FAN type with RelayValue")
        void shouldRejectFanTypeWithRelayValue() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.FAN, RELAY_VALUE_ON)
            );

            assertEquals("Fan value must be FanValue", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Record Equality")
    class RecordEqualityTests {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            final var state1 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);
            final var state2 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);

            assertAll(
                    () -> assertEquals(state1, state2),
                    () -> assertEquals(state1.hashCode(), state2.hashCode())
            );
        }

        @Test
        @DisplayName("should not be equal when id differs")
        void shouldNotBeEqualWhenIdDiffers() {
            final var state1 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);
            final var state2 = new DesiredDeviceState(new DeviceId("different-controller", "different-component"), DeviceType.RELAY, RELAY_VALUE_ON);

            assertNotEquals(state1, state2);
        }

        @Test
        @DisplayName("should not be equal when type differs")
        void shouldNotBeEqualWhenTypeDiffers() {
            final var state1 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);
            final var state2 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.FAN, FAN_VALUE_MID);

            assertNotEquals(state1, state2);
        }

        @Test
        @DisplayName("should not be equal when value differs")
        void shouldNotBeEqualWhenValueDiffers() {
            final var state1 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);
            final var state2 = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_OFF);

            assertNotEquals(state1, state2);
        }

        @Test
        @DisplayName("should not be equal to null")
        void shouldNotBeEqualToNull() {
            final var state = new DesiredDeviceState(TEST_DEVICE_ID, DeviceType.RELAY, RELAY_VALUE_ON);

            assertNotEquals(null, state);
        }
    }
}

