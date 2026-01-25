package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReportedDeviceState Record Tests")
class ReportedDeviceStateTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller1", "relay1");

    @Nested
    @DisplayName("Validation Tests - Required Fields")
    class RequiredFieldsValidationTests {

        @Test
        @DisplayName("Should throw NullPointerException when id is null")
        void shouldThrowWhenIdIsNull() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new ReportedDeviceState(null, DeviceType.RELAY, true, Instant.now(), true)
            );
            assertEquals("Device id must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw NullPointerException when type is null")
        void shouldThrowWhenTypeIsNull() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new ReportedDeviceState(DEVICE_ID, null, true, Instant.now(), true)
            );
            assertEquals("Device type must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw NullPointerException when reportedAt is null")
        void shouldThrowWhenReportedAtIsNull() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, true, null, true)
            );
            assertEquals("ReportedAt must not be null", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Validation Tests - isKnown=true")
    class KnownStateValidationTests {

        @Test
        @DisplayName("Should throw NullPointerException when value is null and isKnown is true")
        void shouldThrowWhenValueIsNullAndKnown() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, null, Instant.now(), true)
            );
            assertEquals("Value must not be null when state is known", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when RELAY value is not Boolean")
        void shouldThrowWhenRelayValueIsNotBoolean() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, "invalid", Instant.now(), true)
            );
            assertEquals("Relay value must be Boolean", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when STEP_RELAY value is not StepRelayState")
        void shouldThrowWhenStepRelayValueIsNotStepRelayState() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new ReportedDeviceState(DEVICE_ID, DeviceType.STEP_RELAY, true, Instant.now(), true)
            );
            assertEquals("Step Relay value must be StepRelayState", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Validation Tests - isKnown=false")
    class UnknownStateValidationTests {

        @Test
        @DisplayName("Should allow null value when isKnown is false")
        void shouldAllowNullValueWhenUnknown() {
            final var state = new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, null, Instant.now(), false);
            
            assertAll(
                () -> assertFalse(state.isKnown()),
                () -> assertNull(state.value())
            );
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when value is not null and isKnown is false")
        void shouldThrowWhenValueIsNotNullAndUnknown() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, true, Instant.now(), false)
            );
            assertEquals("Value must be null when state is unknown", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("Should create valid known state for RELAY")
        void shouldCreateValidKnownRelayState() {
            final var now = Instant.now();
            final var state = new ReportedDeviceState(DEVICE_ID, DeviceType.RELAY, true, now, true);

            assertAll(
                () -> assertEquals(DEVICE_ID, state.id()),
                () -> assertEquals(DeviceType.RELAY, state.type()),
                () -> assertEquals(true, state.value()),
                () -> assertEquals(now, state.reportedAt()),
                () -> assertTrue(state.isKnown())
            );
        }

        @Test
        @DisplayName("Should create valid known state for STEP_RELAY")
        void shouldCreateValidKnownStepRelayState() {
            final var now = Instant.now();
            final var state = new ReportedDeviceState(DEVICE_ID, DeviceType.STEP_RELAY, StepRelayState.LEVEL_3, now, true);

            assertAll(
                () -> assertEquals(StepRelayState.LEVEL_3, state.value()),
                () -> assertTrue(state.isKnown())
            );
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("unknown() should create state with isKnown=false and null value")
        void unknownShouldCreateUnknownState() {
            final var before = Instant.now();
            final var state = ReportedDeviceState.unknown(DEVICE_ID, DeviceType.RELAY);
            final var after = Instant.now();

            assertAll(
                () -> assertEquals(DEVICE_ID, state.id()),
                () -> assertEquals(DeviceType.RELAY, state.type()),
                () -> assertNull(state.value()),
                () -> assertFalse(state.isKnown()),
                () -> assertTrue(state.reportedAt().compareTo(before) >= 0),
                () -> assertTrue(state.reportedAt().compareTo(after) <= 0)
            );
        }

        @Test
        @DisplayName("known() should create state with isKnown=true and provided value")
        void knownShouldCreateKnownState() {
            final var state = ReportedDeviceState.known(DEVICE_ID, DeviceType.STEP_RELAY, StepRelayState.LEVEL_1);

            assertAll(
                () -> assertEquals(StepRelayState.LEVEL_1, state.value()),
                () -> assertTrue(state.isKnown())
            );
        }
    }
}

