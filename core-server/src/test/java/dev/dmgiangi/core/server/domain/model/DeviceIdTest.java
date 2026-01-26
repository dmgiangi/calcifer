package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeviceId")
class DeviceIdTest {

    private static final String VALID_CONTROLLER_ID = "controller-123";
    private static final String VALID_COMPONENT_ID = "component-456";

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @ParameterizedTest(name = "should reject controllerId: \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void shouldRejectInvalidControllerId(String invalidControllerId) {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DeviceId(invalidControllerId, VALID_COMPONENT_ID)
            );
            assertEquals("Controller ID cannot be empty", exception.getMessage());
        }

        @ParameterizedTest(name = "should reject componentId: \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        void shouldRejectInvalidComponentId(String invalidComponentId) {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> new DeviceId(VALID_CONTROLLER_ID, invalidComponentId)
            );
            assertEquals("Component ID cannot be empty", exception.getMessage());
        }

        @Test
        @DisplayName("should accept valid controller and component IDs")
        void shouldAcceptValidIds() {
            final var deviceId = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);

            assertEquals(VALID_CONTROLLER_ID, deviceId.controllerId());
            assertEquals(VALID_COMPONENT_ID, deviceId.componentId());
        }
    }

    @Nested
    @DisplayName("Factory Method - fromString")
    class FactoryMethodTests {

        @Test
        @DisplayName("should parse valid colon-separated string")
        void shouldParseValidString() {
            final var source = VALID_CONTROLLER_ID + ":" + VALID_COMPONENT_ID;

            final var deviceId = DeviceId.fromString(source);

            assertEquals(VALID_CONTROLLER_ID, deviceId.controllerId());
            assertEquals(VALID_COMPONENT_ID, deviceId.componentId());
        }

        @Test
        @DisplayName("should reject string without colon separator")
        void shouldRejectStringWithoutColon() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> DeviceId.fromString("invalid-format")
            );
            assertEquals("Invalid ID format", exception.getMessage());
        }

        @Test
        @DisplayName("should reject string with multiple colons")
        void shouldRejectStringWithMultipleColons() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> DeviceId.fromString("a:b:c")
            );
            assertEquals("Invalid ID format", exception.getMessage());
        }

        @Test
        @DisplayName("should reject empty string")
        void shouldRejectEmptyString() {
            final var exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> DeviceId.fromString("")
            );
            assertEquals("Invalid ID format", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("should return colon-separated format")
        void shouldReturnColonSeparatedFormat() {
            final var deviceId = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);

            final var result = deviceId.toString();

            assertEquals(VALID_CONTROLLER_ID + ":" + VALID_COMPONENT_ID, result);
        }
    }

    @Nested
    @DisplayName("Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal for same controller and component IDs")
        void shouldBeEqualForSameValues() {
            final var deviceId1 = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);
            final var deviceId2 = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);

            assertEquals(deviceId1, deviceId2);
        }

        @Test
        @DisplayName("should not be equal for different controller IDs")
        void shouldNotBeEqualForDifferentControllerId() {
            final var deviceId1 = new DeviceId("controller-A", VALID_COMPONENT_ID);
            final var deviceId2 = new DeviceId("controller-B", VALID_COMPONENT_ID);

            assertNotEquals(deviceId1, deviceId2);
        }

        @Test
        @DisplayName("should not be equal for different component IDs")
        void shouldNotBeEqualForDifferentComponentId() {
            final var deviceId1 = new DeviceId(VALID_CONTROLLER_ID, "component-A");
            final var deviceId2 = new DeviceId(VALID_CONTROLLER_ID, "component-B");

            assertNotEquals(deviceId1, deviceId2);
        }

        @Test
        @DisplayName("should have same hashCode for equal objects")
        void shouldHaveSameHashCodeForEqualObjects() {
            final var deviceId1 = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);
            final var deviceId2 = new DeviceId(VALID_CONTROLLER_ID, VALID_COMPONENT_ID);

            assertEquals(deviceId1.hashCode(), deviceId2.hashCode());
        }
    }
}

