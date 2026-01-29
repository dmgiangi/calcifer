package dev.dmgiangi.core.server.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeviceTwinSnapshot Record Tests")
class DeviceTwinSnapshotTest {

    private static final DeviceId DEVICE_ID = new DeviceId("controller1", "relay1");
    private static final Instant NOW = Instant.now();

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should throw NullPointerException when id is null")
        void shouldThrowWhenIdIsNull() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new DeviceTwinSnapshot(null, DeviceType.RELAY, null, null, null)
            );
            assertEquals("Device id must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw NullPointerException when type is null")
        void shouldThrowWhenTypeIsNull() {
            final var exception = assertThrows(NullPointerException.class, () ->
                new DeviceTwinSnapshot(DEVICE_ID, null, null, null, null)
            );
            assertEquals("Device type must not be null", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when intent type mismatches")
        void shouldThrowWhenIntentTypeMismatches() {
            final var intent = new UserIntent(DEVICE_ID, DeviceType.FAN, new FanValue(3), NOW);

            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null)
            );
            assertEquals("Intent type must match snapshot type", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when reported type mismatches")
        void shouldThrowWhenReportedTypeMismatches() {
            final var reported = new ReportedDeviceState(DEVICE_ID, DeviceType.FAN, new FanValue(0), NOW, true);

            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, reported, null)
            );
            assertEquals("Reported state type must match snapshot type", exception.getMessage());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException when desired type mismatches")
        void shouldThrowWhenDesiredTypeMismatches() {
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.FAN, new FanValue(4));

            final var exception = assertThrows(IllegalArgumentException.class, () ->
                new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, desired)
            );
            assertEquals("Desired state type must match snapshot type", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("Construction Tests")
    class ConstructionTests {

        @Test
        @DisplayName("Should allow all nullable fields to be null")
        void shouldAllowAllNullableFieldsToBeNull() {
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, null);

            assertAll(
                () -> assertEquals(DEVICE_ID, snapshot.id()),
                () -> assertEquals(DeviceType.RELAY, snapshot.type()),
                () -> assertNull(snapshot.intent()),
                () -> assertNull(snapshot.reported()),
                () -> assertNull(snapshot.desired())
            );
        }

        @Test
        @DisplayName("Should create snapshot with only intent")
        void shouldCreateSnapshotWithOnlyIntent() {
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, null, null);

            assertAll(
                () -> assertTrue(snapshot.getIntent().isPresent()),
                () -> assertTrue(snapshot.getReported().isEmpty()),
                () -> assertTrue(snapshot.getDesired().isEmpty())
            );
        }

        @Test
        @DisplayName("Should create complete snapshot with all states")
        void shouldCreateCompleteSnapshot() {
            final var intent = UserIntent.now(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var reported = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));

            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, intent, reported, desired);

            assertAll(
                () -> assertTrue(snapshot.getIntent().isPresent()),
                () -> assertTrue(snapshot.getReported().isPresent()),
                () -> assertTrue(snapshot.getDesired().isPresent())
            );
        }
    }

    @Nested
    @DisplayName("isConverged() Tests")
    class IsConvergedTests {

        @Test
        @DisplayName("Should return false when reported is null")
        void shouldReturnFalseWhenReportedIsNull() {
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, null, desired);

            assertFalse(snapshot.isConverged());
        }

        @Test
        @DisplayName("Should return false when desired is null")
        void shouldReturnFalseWhenDesiredIsNull() {
            final var reported = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, reported, null);

            assertFalse(snapshot.isConverged());
        }

        @Test
        @DisplayName("Should return false when reported state is unknown")
        void shouldReturnFalseWhenReportedIsUnknown() {
            final var reported = ReportedDeviceState.unknown(DEVICE_ID, DeviceType.RELAY);
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, reported, desired);

            assertFalse(snapshot.isConverged());
        }

        @Test
        @DisplayName("Should return false when values do not match")
        void shouldReturnFalseWhenValuesDoNotMatch() {
            final var reported = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(false));
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, reported, desired);

            assertFalse(snapshot.isConverged());
        }

        @Test
        @DisplayName("Should return true when values match")
        void shouldReturnTrueWhenValuesMatch() {
            final var reported = ReportedDeviceState.known(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.RELAY, new RelayValue(true));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.RELAY, null, reported, desired);

            assertTrue(snapshot.isConverged());
        }

        @Test
        @DisplayName("Should return true when FanValue values match")
        void shouldReturnTrueWhenFanValuesMatch() {
            final var reported = ReportedDeviceState.known(DEVICE_ID, DeviceType.FAN, new FanValue(3));
            final var desired = new DesiredDeviceState(DEVICE_ID, DeviceType.FAN, new FanValue(3));
            final var snapshot = new DeviceTwinSnapshot(DEVICE_ID, DeviceType.FAN, null, reported, desired);

            assertTrue(snapshot.isConverged());
        }
    }

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryMethodTests {

        @Test
        @DisplayName("empty() should create snapshot with all null states")
        void emptyShouldCreateEmptySnapshot() {
            final var snapshot = DeviceTwinSnapshot.empty(DEVICE_ID, DeviceType.RELAY);

            assertAll(
                () -> assertEquals(DEVICE_ID, snapshot.id()),
                () -> assertEquals(DeviceType.RELAY, snapshot.type()),
                () -> assertNull(snapshot.intent()),
                () -> assertNull(snapshot.reported()),
                () -> assertNull(snapshot.desired()),
                () -> assertFalse(snapshot.isConverged())
            );
        }
    }
}

