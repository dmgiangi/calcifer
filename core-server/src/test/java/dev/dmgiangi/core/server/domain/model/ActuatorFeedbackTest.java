package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ActuatorFeedback")
class ActuatorFeedbackTest {

    private static final DeviceId VALID_DEVICE_ID = new DeviceId("controller-1", "component-1");
    private static final DeviceType VALID_DEVICE_TYPE = DeviceType.FAN;
    private static final String VALID_RAW_VALUE = "128";
    private static final Instant VALID_RECEIVED_AT = Instant.parse("2026-01-26T12:00:00Z");

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should reject null device ID")
        void shouldRejectNullDeviceId() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActuatorFeedback(null, VALID_DEVICE_TYPE, VALID_RAW_VALUE, VALID_RECEIVED_AT)
            );

            assertEquals("Device ID cannot be null", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null device type")
        void shouldRejectNullDeviceType() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActuatorFeedback(VALID_DEVICE_ID, null, VALID_RAW_VALUE, VALID_RECEIVED_AT)
            );

            assertEquals("Device type cannot be null", exception.getMessage());
        }

        @ParameterizedTest(name = "should reject raw value: \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        @DisplayName("should reject null, empty, or blank raw value")
        void shouldRejectInvalidRawValue(final String invalidRawValue) {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActuatorFeedback(VALID_DEVICE_ID, VALID_DEVICE_TYPE, invalidRawValue, VALID_RECEIVED_AT)
            );

            assertEquals("Raw value cannot be empty", exception.getMessage());
        }

        @Test
        @DisplayName("should reject null received timestamp")
        void shouldRejectNullReceivedAt() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActuatorFeedback(VALID_DEVICE_ID, VALID_DEVICE_TYPE, VALID_RAW_VALUE, null)
            );

            assertEquals("Received timestamp cannot be null", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create valid ActuatorFeedback with all fields")
        void shouldCreateValidActuatorFeedback() {
            final var feedback = new ActuatorFeedback(
                    VALID_DEVICE_ID,
                    VALID_DEVICE_TYPE,
                    VALID_RAW_VALUE,
                    VALID_RECEIVED_AT
            );

            assertAll(
                    () -> assertEquals(VALID_DEVICE_ID, feedback.id()),
                    () -> assertEquals(VALID_DEVICE_TYPE, feedback.type()),
                    () -> assertEquals(VALID_RAW_VALUE, feedback.rawValue()),
                    () -> assertEquals(VALID_RECEIVED_AT, feedback.receivedAt())
            );
        }

        @Test
        @DisplayName("should create ActuatorFeedback with RELAY device type")
        void shouldCreateActuatorFeedbackWithRelayType() {
            final var feedback = new ActuatorFeedback(
                    VALID_DEVICE_ID,
                    DeviceType.RELAY,
                    "ON",
                    VALID_RECEIVED_AT
            );

            assertEquals(DeviceType.RELAY, feedback.type());
        }

        @Test
        @DisplayName("should accept raw value with whitespace content")
        void shouldAcceptRawValueWithWhitespaceContent() {
            final var rawValueWithWhitespace = "  128  ";

            final var feedback = new ActuatorFeedback(
                    VALID_DEVICE_ID,
                    VALID_DEVICE_TYPE,
                    rawValueWithWhitespace,
                    VALID_RECEIVED_AT
            );

            assertEquals(rawValueWithWhitespace, feedback.rawValue());
        }
    }

    @Nested
    @DisplayName("Record Equality")
    class EqualityTests {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            final var feedback1 = new ActuatorFeedback(
                    VALID_DEVICE_ID, VALID_DEVICE_TYPE, VALID_RAW_VALUE, VALID_RECEIVED_AT
            );
            final var feedback2 = new ActuatorFeedback(
                    VALID_DEVICE_ID, VALID_DEVICE_TYPE, VALID_RAW_VALUE, VALID_RECEIVED_AT
            );

            assertEquals(feedback1, feedback2);
            assertEquals(feedback1.hashCode(), feedback2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when raw value differs")
        void shouldNotBeEqualWhenRawValueDiffers() {
            final var feedback1 = new ActuatorFeedback(
                    VALID_DEVICE_ID, VALID_DEVICE_TYPE, "128", VALID_RECEIVED_AT
            );
            final var feedback2 = new ActuatorFeedback(
                    VALID_DEVICE_ID, VALID_DEVICE_TYPE, "255", VALID_RECEIVED_AT
            );

            assertNotEquals(feedback1, feedback2);
        }
    }
}

