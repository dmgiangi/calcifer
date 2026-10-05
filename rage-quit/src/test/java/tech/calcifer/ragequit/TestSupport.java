package tech.calcifer.ragequit;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static tech.calcifer.ragequit.TrackingDtos.*;

final class TestSupport {
    static final String DEM = "user:admin";
    static final String PUGLIENS = "user:moody";
    static final String FREVA = "user:frevadiscor";
    static final LocalDate START = LocalDate.of(2026, 3, 28);
    static final Instant NOW = Instant.parse("2026-03-30T10:00:00Z");

    record Fixture(RageQuitProperties properties, SqliteDatabase database, EventStore store, InsightsService insights) { }

    static Fixture fixture(Path file, Clock clock, LocalDate start) {
        var properties = new RageQuitProperties("https://auth.calcifer.tech", "rage-quit", "synthetic-test-fixture", file, start);
        var source = new StorageConfiguration().dataSource(properties);
        var database = new SqliteDatabase(source, properties, clock);
        var store = new EventStore(database, properties, clock);
        return new Fixture(properties, database, store, new InsightsService(store, properties, clock));
    }

    static CreateEvent create(EventType type, String key, Instant instant) {
        return new CreateEvent(type, key, new TimeInput(null, instant, null, null));
    }

    static TimeInput now() { return new TimeInput(true, null, null, null); }
    static TimeInput at(String instant) { return new TimeInput(null, Instant.parse(instant), null, null); }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time;
        private final ZoneId zone;
        MutableClock(Instant time) { this(new AtomicReference<>(time), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> time, ZoneId zone) { this.time = time; this.zone = zone; }
        void set(Instant instant) { time.set(instant); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(time, zone); }
        @Override public Instant instant() { return time.get(); }
    }
}
