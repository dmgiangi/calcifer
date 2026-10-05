package tech.calcifer.ragequit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static tech.calcifer.ragequit.TestSupport.*;
import static tech.calcifer.ragequit.TrackingDtos.*;

class StorageTest {
    @TempDir Path directory;
    MutableClock clock;
    Fixture fixture;

    @BeforeEach void setup() {
        clock = new MutableClock(NOW);
        fixture = fixture(directory.resolve("records.sqlite"), clock, START);
    }

    @Test void freshSchemaIsVersionedDurableAndReady() throws Exception {
        assertThat(fixture.database().healthy()).isTrue();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.properties().database());
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA user_version")) { assertThat(rows.getInt(1)).isEqualTo(1); }
            try (var rows = statement.executeQuery("PRAGMA journal_mode")) { assertThat(rows.getString(1)).isEqualTo("delete"); }
        }
        fixture.database().read(connection -> {
            try (var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("PRAGMA synchronous")) { assertThat(rows.getInt(1)).isEqualTo(2); }
                try (var rows = statement.executeQuery("PRAGMA foreign_keys")) { assertThat(rows.getInt(1)).isEqualTo(1); }
                try (var rows = statement.executeQuery("PRAGMA busy_timeout")) { assertThat(rows.getInt(1)).isEqualTo(2000); }
            }
            return null;
        });
    }

    @Test void nowRetriesUseOriginalPayloadAcrossTimeAndRestart() {
        var input = new CreateEvent(EventType.SMOKED, "same-key", now());
        Event first = fixture.store().create(DEM, input);
        clock.set(NOW.plusSeconds(600));
        assertThat(fixture.store().create(DEM, input)).isEqualTo(first);
        var reopened = fixture(fixture.properties().database(), clock, START);
        assertThat(reopened.store().create(DEM, input)).isEqualTo(first);
        assertThat(reopened.store().history(DEM).events()).hasSize(1);
        assertThat(first.effectiveAt()).isEqualTo(NOW);
    }

    @Test void distinctKeysAndOwnersRemainIndependentButConflictingReuseFails() {
        var input = new CreateEvent(EventType.RESISTED, "key", now());
        fixture.store().create(DEM, input);
        fixture.store().create(PUGLIENS, input);
        fixture.store().create(DEM, new CreateEvent(EventType.RESISTED, "other", now()));
        assertThat(fixture.store().history(DEM).events()).hasSize(2);
        assertThatThrownBy(() -> fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "key", now())))
                .isInstanceOf(ApiException.class).hasMessage("conflict");
        assertThatThrownBy(() -> fixture.store().create(DEM, create(EventType.RESISTED, "key", NOW)))
                .hasMessage("conflict");
    }

    @Test void futureAndPreStartInstantsAndMalformedSelectionsFail() {
        Instant boundary = START.atStartOfDay(ROME).toInstant();
        fixture.store().create(DEM, create(EventType.RESISTED, "boundary", boundary));
        for (Instant invalid : new Instant[]{boundary.minusNanos(1), NOW.plusNanos(1)}) {
            assertThatThrownBy(() -> fixture.store().create(DEM, create(EventType.SMOKED, "invalid", invalid)))
                    .hasMessage("invalid_request");
        }
        assertThatThrownBy(() -> new TimeInput(true, NOW, null, null).resolve(NOW, START)).hasMessage("invalid_request");
        assertThatThrownBy(() -> new TimeInput(null, null, null, null).resolve(NOW, START)).hasMessage("invalid_request");
        assertThatThrownBy(() -> new TimeInput(false, NOW, null, null).resolve(NOW, START)).hasMessage("invalid_request");
        assertThatThrownBy(() -> new TimeInput(null, NOW, null, ZoneOffset.UTC).resolve(NOW, START)).hasMessage("invalid_request");
        assertThatThrownBy(() -> fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, " ", now())))
                .hasMessage("invalid_request");
        assertThat(fixture.store().history(DEM).events()).hasSize(1);
    }

    @Test void romeGapAndOverlapRequireExplicitValidOffset() {
        Instant later = Instant.parse("2026-10-26T10:00:00Z");
        LocalDateTime gap = LocalDateTime.parse("2026-03-29T02:30:00");
        LocalDateTime overlap = LocalDateTime.parse("2026-10-25T02:30:00");
        for (ZoneOffset offset : new ZoneOffset[]{null, ZoneOffset.ofHours(1), ZoneOffset.ofHours(2)}) {
            assertThatThrownBy(() -> new TimeInput(null, null, gap, offset).resolve(later, START)).hasMessage("invalid_request");
        }
        assertThatThrownBy(() -> new TimeInput(null, null, overlap, null).resolve(later, START)).hasMessage("invalid_request");
        assertThatThrownBy(() -> new TimeInput(null, null, overlap, ZoneOffset.UTC).resolve(later, START)).hasMessage("invalid_request");
        Instant first = new TimeInput(null, null, overlap, ZoneOffset.ofHours(2)).resolve(later, START);
        Instant second = new TimeInput(null, null, overlap, ZoneOffset.ofHours(1)).resolve(later, START);
        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofHours(1));
        assertThatThrownBy(() -> new TimeInput(null, null, LocalDateTime.parse("2026-03-28T12:00:00"), null)
                .resolve(later, START)).hasMessage("invalid_request");
    }

    @Test void ownerOnlyDetailsAndMutationsIncludingAdministrator() {
        Event event = fixture.store().create(PUGLIENS, new CreateEvent(EventType.SMOKED, "private", now()));
        assertThat(fixture.store().history(DEM).events()).isEmpty();
        assertThatThrownBy(() -> fixture.store().get(DEM, event.id())).hasMessage("not_found");
        assertThatThrownBy(() -> fixture.store().update(DEM, event.id(), new UpdateEvent(1L, EventType.RESISTED, now())))
                .hasMessage("not_found");
        assertThatThrownBy(() -> fixture.store().delete(DEM, event.id(), 1)).hasMessage("not_found");
        assertThatThrownBy(() -> fixture.store().history("unknown")).isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);
        assertThat(fixture.store().get(PUGLIENS, event.id())).isEqualTo(event);
    }

    @Test void correctionsUseVersionsAndDeletedRetriesCannotResurrect() {
        var input = new CreateEvent(EventType.SMOKED, "retry", now());
        Event original = fixture.store().create(DEM, input);
        Event changed = fixture.store().update(DEM, original.id(), new UpdateEvent(1L, EventType.RESISTED, at("2026-03-28T12:00:00Z")));
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.createdAt()).isEqualTo(original.createdAt());
        assertThat(fixture.store().create(DEM, input)).isEqualTo(changed);
        assertThatThrownBy(() -> fixture.store().update(DEM, original.id(), new UpdateEvent(1L, EventType.SMOKED, now())))
                .hasMessage("conflict");
        assertThatThrownBy(() -> fixture.store().delete(DEM, original.id(), 1)).hasMessage("conflict");
        fixture.store().delete(DEM, original.id(), 2);
        assertThatThrownBy(() -> fixture.store().create(DEM, input)).hasMessage("conflict");
        assertThat(fixture.store().history(DEM).events()).isEmpty();
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
    }

    @Test void zeroDeclarationsRemainExplicitAndSmokingInvalidatesAtomically() {
        fixture.store().create(DEM, create(EventType.RESISTED, "resist", Instant.parse("2026-03-28T12:00:00Z")));
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
        fixture.store().confirmZero(DEM, START);
        assertThat(fixture.store().history(DEM).zeroDeclarations()).hasSize(1);
        fixture.store().withdrawZero(DEM, START);
        assertThat(fixture.store().history(DEM).events()).hasSize(1);
        fixture.store().confirmZero(DEM, START);
        Event cigarette = fixture.store().create(DEM, create(EventType.SMOKED, "smoke", Instant.parse("2026-03-28T13:00:00Z")));
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
        assertThatThrownBy(() -> fixture.store().confirmZero(DEM, START)).hasMessage("conflict");
        fixture.store().delete(DEM, cigarette.id(), 1);
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
        assertThat(fixture.store().confirmZero(DEM, fixture.store().today()).inProgress()).isTrue();
        assertThatThrownBy(() -> fixture.store().confirmZero(DEM, fixture.store().today().plusDays(1))).hasMessage("invalid_request");
        assertThatThrownBy(() -> fixture.store().withdrawZero(DEM, START.minusDays(1))).hasMessage("invalid_request");
    }

    @Test void movingOrConvertingCigarettesInvalidatesOnlyDestinationDeclaration() {
        fixture.store().confirmZero(DEM, START);
        fixture.store().confirmZero(DEM, START.plusDays(1));
        Event event = fixture.store().create(DEM, create(EventType.RESISTED, "moving", Instant.parse("2026-03-28T13:00:00Z")));
        fixture.store().update(DEM, event.id(), new UpdateEvent(1L, EventType.SMOKED, at("2026-03-29T13:00:00Z")));
        assertThat(fixture.store().history(DEM).zeroDeclarations()).extracting(ZeroDeclaration::date).containsExactly(START);
        fixture.store().update(DEM, event.id(), new UpdateEvent(2L, EventType.SMOKED, at("2026-03-28T13:00:00Z")));
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
    }

    @Test void reopeningPreservesEventsZerosAndCommonPeriod() {
        Event event = fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "durable", now()));
        fixture.store().confirmZero(PUGLIENS, START);
        var before = fixture.insights().insights(DEM);
        Fixture restarted = fixture(fixture.properties().database(), clock, START);
        assertThat(restarted.store().get(DEM, event.id())).isEqualTo(event);
        assertThat(restarted.store().history(PUGLIENS).zeroDeclarations()).hasSize(1);
        assertThat(restarted.insights().insights(DEM)).isEqualTo(before);
    }

    @Test void rollbackDoesNotLeavePartialData() {
        assertThatThrownBy(() -> fixture.database().write(connection -> {
            SqliteDatabase.execute(connection, "INSERT INTO zero_declarations(owner,date,confirmed_at) VALUES(?,?,?)",
                    DEM, START.toString(), NOW.toString());
            throw ApiException.invalid();
        })).hasMessage("invalid_request");
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
    }

    @Test void failedZeroInvalidationRollsBackTheEventAndSubmissionTogether() {
        fixture.store().confirmZero(DEM, START);
        fixture.database().write(connection -> {
            SqliteDatabase.execute(connection, "CREATE TRIGGER reject_zero_delete BEFORE DELETE ON zero_declarations BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
            return null;
        });
        var input = create(EventType.SMOKED, "atomic-zero", Instant.parse("2026-03-28T12:00:00Z"));
        assertThatThrownBy(() -> fixture.store().create(DEM, input)).hasMessage("storage_unavailable");
        assertThat(fixture.store().history(DEM).events()).isEmpty();
        assertThat(fixture.store().history(DEM).zeroDeclarations()).hasSize(1);
        fixture.database().write(connection -> { SqliteDatabase.execute(connection, "DROP TRIGGER reject_zero_delete"); return null; });
        fixture.store().create(DEM, input);
        assertThat(fixture.store().history(DEM).events()).hasSize(1);
        assertThat(fixture.store().history(DEM).zeroDeclarations()).isEmpty();
    }

    @Test void concurrentWritesAndRetriesAreSerialized() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Callable<Event>>();
            for (int i = 0; i < 16; i++) {
                calls.add(() -> { gate.await(); return fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "concurrent-key", now())); });
            }
            var futures = calls.stream().map(executor::submit).toList();
            gate.countDown();
            var ids = new java.util.HashSet<String>();
            for (var future : futures) ids.add(future.get().id());
            assertThat(ids).hasSize(1);
            calls.clear();
            for (int i = 0; i < 16; i++) {
                String key = "distinct-" + i;
                calls.add(() -> fixture.store().create(DEM, new CreateEvent(EventType.RESISTED, key, now())));
            }
            for (var future : executor.invokeAll(calls)) future.get();
            assertThat(fixture.store().history(DEM).events()).hasSize(17);
        }
    }

    @Test void concurrentCorrectionsCannotSilentlyOverwrite() throws Exception {
        Event original = fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "race", now()));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            Callable<Boolean> edit = () -> {
                gate.await();
                try { fixture.store().update(DEM, original.id(), new UpdateEvent(1L, EventType.RESISTED, now())); return true; }
                catch (ApiException e) { assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT); return false; }
            };
            var first = executor.submit(edit);
            var second = executor.submit(edit);
            gate.countDown();
            assertThat(first.get() ^ second.get()).isTrue();
            assertThat(fixture.store().get(DEM, original.id()).version()).isEqualTo(2);
        }
    }

    @Test void externalWriterContentionFailsWithinBoundedTimeout() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.properties().database());
             var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            long start = System.nanoTime();
            assertThatThrownBy(() -> fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "busy", now())))
                    .hasMessage("storage_unavailable");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
            statement.execute("ROLLBACK");
        }
        assertThat(fixture.store().history(DEM).events()).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints = {-1, 2, 99})
    void incompatibleSchemaIsRejectedAndNotOverwritten(int version) {
        fixture.database().write(connection -> { SqliteDatabase.execute(connection, "PRAGMA user_version=" + version); return null; });
        assertThat(fixture.database().healthy()).isFalse();
        assertThatThrownBy(() -> fixture(fixture.properties().database(), clock, START)).hasMessage("Storage schema initialization failed");
    }

    @Test void changedStartDateFutureStartAndInaccessibleStorageFailClosed() {
        assertThatThrownBy(() -> fixture(fixture.properties().database(), clock, START.plusDays(1)))
                .hasMessage("Storage schema initialization failed");
        assertThat(fixture.database().healthy()).isTrue();
        assertThatThrownBy(() -> fixture(directory.resolve("future.sqlite"), clock, LocalDate.of(2026, 4, 1)))
                .hasMessage("RAGE_QUIT_START_DATE cannot be in the future");
        assertThatThrownBy(() -> fixture(directory.resolve("missing/records.sqlite"), clock, START))
                .hasMessage("Storage schema initialization failed");
    }

    @Test void unversionedNonemptyAndMissingColumnsAreRejected() throws Exception {
        Path unversioned = directory.resolve("legacy.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + unversioned); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unknown(value TEXT)");
        }
        assertThatThrownBy(() -> fixture(unversioned, clock, START)).hasMessage("Storage schema initialization failed");
        fixture.database().write(connection -> { SqliteDatabase.execute(connection, "ALTER TABLE events RENAME COLUMN version TO obsolete"); return null; });
        assertThat(fixture.database().healthy()).isFalse();
        assertThatThrownBy(() -> fixture(fixture.properties().database(), clock, START)).hasMessage("Storage schema initialization failed");
    }

    @Test void lostStorageIsNotRecreatedByRuntimeReadsOrWrites() throws Exception {
        Path database = fixture.properties().database();
        Path retained = directory.resolve("retained.sqlite");
        java.nio.file.Files.move(database, retained);
        assertThat(fixture.database().healthy()).isFalse();
        assertThatThrownBy(() -> fixture.store().history(DEM)).hasMessage("storage_unavailable");
        assertThatThrownBy(() -> fixture.store().create(DEM, new CreateEvent(EventType.SMOKED, "lost", now())))
                .hasMessage("storage_unavailable");
        assertThat(java.nio.file.Files.exists(database)).isFalse();
        java.nio.file.Files.move(retained, database);
        assertThat(fixture.database().healthy()).isTrue();
    }
}
