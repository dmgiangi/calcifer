package tech.calcifer.ragequit;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tech.calcifer.ragequit.SqliteDatabase.execute;
import static tech.calcifer.ragequit.TrackingDtos.*;

@Service
public class EventStore {
    public record OwnedEvent(String owner, Event event) { }
    public record Snapshot(List<OwnedEvent> events, Map<String, Map<LocalDate, Instant>> zeros) { }
    private static final Comparator<Event> EVENT_ORDER = Comparator.comparing(Event::effectiveAt).thenComparing(Event::id);
    private final SqliteDatabase database;
    private final RageQuitProperties properties;
    private final Clock clock;

    public EventStore(SqliteDatabase database, RageQuitProperties properties, Clock clock) {
        this.database = database;
        this.properties = properties;
        this.clock = clock;
    }

    public Event create(String owner, CreateEvent input) {
        Participants.requireSubject(owner);
        if (input == null || input.type() == null || input.time() == null || input.idempotencyKey() == null
                || !input.idempotencyKey().matches("[A-Za-z0-9_-]{1,128}")) throw ApiException.invalid();
        return database.write(connection -> {
            try (var statement = connection.prepareStatement("SELECT payload,event_id FROM submissions WHERE owner=? AND submission_key=?")) {
                statement.setString(1, owner);
                statement.setString(2, input.idempotencyKey());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        if (!input.originalPayload().equals(rows.getString("payload")) || rows.getString("event_id") == null) {
                            throw ApiException.conflict();
                        }
                        return event(connection, owner, rows.getString("event_id"));
                    }
                }
            }
            Instant now = clock.instant();
            Instant effective = input.time().resolve(now, properties.startDate());
            Event event = new Event(UUID.randomUUID().toString(), input.type(), effective, now, now, 1);
            execute(connection, "INSERT INTO events(id,owner,type,effective_at,created_at,updated_at,version) VALUES(?,?,?,?,?,?,1)",
                    event.id(), owner, event.type().name(), effective.toString(), now.toString(), now.toString());
            execute(connection, "INSERT INTO submissions(owner,submission_key,payload,event_id) VALUES(?,?,?,?)",
                    owner, input.idempotencyKey(), input.originalPayload(), event.id());
            invalidateZero(connection, owner, event);
            return event;
        });
    }

    public Event get(String owner, String id) {
        Participants.requireSubject(owner);
        return database.read(connection -> event(connection, owner, id));
    }

    public Event update(String owner, String id, UpdateEvent input) {
        Participants.requireSubject(owner);
        if (input == null || input.version() == null || input.version() < 1 || input.type() == null || input.time() == null) {
            throw ApiException.invalid();
        }
        return database.write(connection -> {
            Event previous = event(connection, owner, id);
            if (previous.version() != input.version()) throw ApiException.conflict();
            Instant now = clock.instant();
            Instant effective = input.time().resolve(now, properties.startDate());
            int updated = execute(connection, "UPDATE events SET type=?,effective_at=?,updated_at=?,version=version+1 WHERE id=? AND owner=? AND version=?",
                    input.type().name(), effective.toString(), now.toString(), id, owner, input.version());
            if (updated != 1) throw ApiException.conflict();
            Event result = new Event(id, input.type(), effective, previous.createdAt(), now, previous.version() + 1);
            invalidateZero(connection, owner, result);
            return result;
        });
    }

    public void delete(String owner, String id, long version) {
        Participants.requireSubject(owner);
        if (version < 1) throw ApiException.invalid();
        database.write(connection -> {
            Event previous = event(connection, owner, id);
            if (previous.version() != version) throw ApiException.conflict();
            if (execute(connection, "DELETE FROM events WHERE id=? AND owner=? AND version=?", id, owner, version) != 1) {
                throw ApiException.conflict();
            }
            // Keep the submission tombstone. A retry must not recreate an explicitly deleted event.
            return null;
        });
    }

    public ZeroDeclaration confirmZero(String owner, LocalDate date) {
        Participants.requireSubject(owner);
        validateDate(date);
        return database.write(connection -> {
            if (hasSmoking(connection, owner, date)) throw ApiException.conflict();
            Instant now = clock.instant();
            execute(connection, "INSERT INTO zero_declarations(owner,date,confirmed_at) VALUES(?,?,?) ON CONFLICT(owner,date) DO UPDATE SET confirmed_at=excluded.confirmed_at",
                    owner, date.toString(), now.toString());
            return new ZeroDeclaration(date, now, date.equals(today()));
        });
    }

    public void withdrawZero(String owner, LocalDate date) {
        Participants.requireSubject(owner);
        validateDate(date);
        database.write(connection -> {
            execute(connection, "DELETE FROM zero_declarations WHERE owner=? AND date=?", owner, date.toString());
            return null;
        });
    }

    public History history(String owner) {
        Participants.requireSubject(owner);
        return database.read(connection -> {
            List<Event> events = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT * FROM events WHERE owner=?")) {
                statement.setString(1, owner);
                try (var rows = statement.executeQuery()) { while (rows.next()) events.add(mapEvent(rows)); }
            }
            events.sort(EVENT_ORDER.reversed());
            List<ZeroDeclaration> zeros = new ArrayList<>();
            try (var statement = connection.prepareStatement("SELECT date,confirmed_at FROM zero_declarations WHERE owner=? ORDER BY date")) {
                statement.setString(1, owner);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        LocalDate date = LocalDate.parse(rows.getString("date"));
                        zeros.add(new ZeroDeclaration(date, Instant.parse(rows.getString("confirmed_at")), date.equals(today())));
                    }
                }
            }
            return new History(List.copyOf(events), List.copyOf(zeros));
        });
    }

    public Snapshot snapshot() {
        return database.read(connection -> {
            List<OwnedEvent> events = new ArrayList<>();
            Map<String, Map<LocalDate, Instant>> zeros = new HashMap<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM events")) {
                while (rows.next()) events.add(new OwnedEvent(rows.getString("owner"), mapEvent(rows)));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM zero_declarations")) {
                while (rows.next()) zeros.computeIfAbsent(rows.getString("owner"), ignored -> new HashMap<>())
                        .put(LocalDate.parse(rows.getString("date")), Instant.parse(rows.getString("confirmed_at")));
            }
            zeros.replaceAll((owner, dates) -> Map.copyOf(dates));
            return new Snapshot(List.copyOf(events), Map.copyOf(zeros));
        });
    }

    private static Event event(Connection connection, String owner, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM events WHERE id=? AND owner=?")) {
            statement.setString(1, id);
            statement.setString(2, owner);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw ApiException.missing();
                return mapEvent(rows);
            }
        }
    }

    private static Event mapEvent(ResultSet rows) throws SQLException {
        return new Event(rows.getString("id"), EventType.valueOf(rows.getString("type")),
                Instant.parse(rows.getString("effective_at")), Instant.parse(rows.getString("created_at")),
                Instant.parse(rows.getString("updated_at")), rows.getLong("version"));
    }

    private static boolean hasSmoking(Connection connection, String owner, LocalDate date) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT effective_at FROM events WHERE owner=? AND type='SMOKED'")) {
            statement.setString(1, owner);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (Instant.parse(rows.getString(1)).atZone(ROME).toLocalDate().equals(date)) return true;
                }
            }
            return false;
        }
    }

    private static void invalidateZero(Connection connection, String owner, Event event) throws SQLException {
        if (event.type() == EventType.SMOKED) {
            execute(connection, "DELETE FROM zero_declarations WHERE owner=? AND date=?",
                    owner, event.effectiveAt().atZone(ROME).toLocalDate().toString());
        }
    }

    private void validateDate(LocalDate date) {
        if (date == null || date.isBefore(properties.startDate()) || date.isAfter(today())) throw ApiException.invalid();
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(ROME)); }
}
