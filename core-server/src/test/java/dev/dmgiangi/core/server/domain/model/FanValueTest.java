package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FanValue")
class FanValueTest {

    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("should accept minimum speed (0)")
        void shouldAcceptMinimumSpeed_0() {
            final var fanValue = new FanValue(0);
            assertEquals(0, fanValue.speed());
        }

        @Test
        @DisplayName("should accept maximum speed (4)")
        void shouldAcceptMaximumSpeed_4() {
            final var fanValue = new FanValue(4);
            assertEquals(4, fanValue.speed());
        }

        @Test
        @DisplayName("should accept mid-range speed (2)")
        void shouldAcceptMidRangeSpeed_2() {
            final var fanValue = new FanValue(2);
            assertEquals(2, fanValue.speed());
        }

        @ParameterizedTest
        @DisplayName("should accept valid speeds within range")
        @ValueSource(ints = {1, 2, 3})
        void shouldAcceptValidSpeedsWithinRange(int speed) {
            final var fanValue = new FanValue(speed);
            assertEquals(speed, fanValue.speed());
        }

        @Test
        @DisplayName("should reject negative speed")
        void shouldRejectNegativeSpeed() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                    new FanValue(-1)
            );
            assertEquals("Fan speed must be between 0 and 4", exception.getMessage());
        }

        @Test
        @DisplayName("should reject speed above maximum (5)")
        void shouldRejectSpeedAboveMax() {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                    new FanValue(5)
            );
            assertEquals("Fan speed must be between 0 and 4", exception.getMessage());
        }

        @ParameterizedTest
        @DisplayName("should reject speeds outside valid range")
        @ValueSource(ints = {-100, -1, 5, 10, 100})
        void shouldRejectSpeedsOutsideValidRange(int invalidSpeed) {
            final var exception = assertThrows(IllegalArgumentException.class, () ->
                    new FanValue(invalidSpeed)
            );
            assertEquals("Fan speed must be between 0 and 4", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Record Accessors")
    class RecordAccessorTests {

        @Test
        @DisplayName("should return speed correctly")
        void shouldReturnSpeedCorrectly() {
            final var fanValue = new FanValue(4);
            assertEquals(4, fanValue.speed());
        }
    }

    @Nested
    @DisplayName("DeviceValue Interface")
    class DeviceValueInterfaceTests {

        @Test
        @DisplayName("should implement DeviceValue interface")
        void shouldImplementDeviceValueInterface() {
            final var fanValue = new FanValue(2);
            assertInstanceOf(DeviceValue.class, fanValue);
        }
    }

    @Nested
    @DisplayName("Equality and HashCode")
    class EqualityTests {

        @Test
        @DisplayName("should be equal when speeds are the same")
        void shouldBeEqualWhenSpeedsAreSame() {
            final var fanValue1 = new FanValue(4);
            final var fanValue2 = new FanValue(4);
            assertEquals(fanValue1, fanValue2);
        }

        @Test
        @DisplayName("should not be equal when speeds differ")
        void shouldNotBeEqualWhenSpeedsDiffer() {
            final var fanValue1 = new FanValue(2);
            final var fanValue2 = new FanValue(3);
            assertNotEquals(fanValue1, fanValue2);
        }

        @Test
        @DisplayName("should have same hashCode when speeds are the same")
        void shouldHaveSameHashCodeWhenSpeedsAreSame() {
            final var fanValue1 = new FanValue(4);
            final var fanValue2 = new FanValue(4);
            assertEquals(fanValue1.hashCode(), fanValue2.hashCode());
        }
    }
}

