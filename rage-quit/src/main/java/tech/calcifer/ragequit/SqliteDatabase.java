package tech.calcifer.ragequit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteOpenMode;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class SqliteDatabase {
    private static final int SCHEMA_VERSION = 1;
    private final DataSource source;
    private final RageQuitProperties properties;
    private final ReentrantLock writer = new ReentrantLock(true);
    private final Semaphore pendingWrites = new Semaphore(32);

    @FunctionalInterface
    public interface Work<T> { T run(Connection connection) throws SQLException; }

    public SqliteDatabase(DataSource source, RageQuitProperties properties, Clock clock) {
        this.source = source;
        this.properties = properties;
        if (properties.startDate().isAfter(LocalDate.now(clock.withZone(TrackingDtos.ROME)))) {
            throw new IllegalArgumentException("RAGE_QUIT_START_DATE cannot be in the future");
        }
        try {
            initialize();
            // Startup may create a fresh file. Runtime connections must never recreate lost storage.
            if (source instanceof SQLiteDataSource sqlite) sqlite.getConfig().resetOpenMode(SQLiteOpenMode.CREATE);
        } catch (SQLException | IOException e) {
            // Do not attach SQL exceptions, paths, or request data to routine startup diagnostics.
            throw new IllegalStateException("Storage schema initialization failed");
        }
    }

    private void initialize() throws SQLException, IOException {
        try (var connection = source.getConnection()) {
            execute(connection, "BEGIN IMMEDIATE");
            try {
                int version = schemaVersion(connection);
                if (version == 0) {
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                        if (rows.next() && rows.getInt(1) != 0) {
                            throw new SQLException("Unversioned nonempty database");
                        }
                    }
                    try (var input = new ClassPathResource("db/migration/V1.sql").getInputStream()) {
                        for (String sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                            if (!sql.isBlank()) execute(connection, sql);
                        }
                    }
                    execute(connection, "INSERT INTO settings(key,value) VALUES('start_date',?)", properties.startDate().toString());
                    execute(connection, "PRAGMA user_version=1");
                }
                verifySchema(connection);
                execute(connection, "COMMIT");
            } catch (SQLException | IOException | RuntimeException e) {
                execute(connection, "ROLLBACK");
                throw e;
            }
        }
    }

    public <T> T read(Work<T> work) {
        if (!Files.isRegularFile(properties.database())) throw ApiException.unavailable();
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            throw ApiException.unavailable();
        }
    }

    public <T> T write(Work<T> work) {
        if (!pendingWrites.tryAcquire()) throw ApiException.unavailable();
        boolean locked = false;
        try {
            locked = writer.tryLock(2, TimeUnit.SECONDS);
            if (!locked || !Files.isRegularFile(properties.database())) throw ApiException.unavailable();
            try (var connection = source.getConnection()) {
                execute(connection, "BEGIN IMMEDIATE");
                try {
                    T result = work.run(connection);
                    execute(connection, "COMMIT");
                    return result;
                } catch (SQLException | RuntimeException e) {
                    execute(connection, "ROLLBACK");
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable();
        } catch (SQLException e) {
            throw ApiException.unavailable();
        } finally {
            if (locked) writer.unlock();
            pendingWrites.release();
        }
    }

    public boolean healthy() {
        if (!Files.isReadable(properties.database()) || !Files.isWritable(properties.database())
                || !Files.isWritable(properties.database().getParent())) return false;
        try {
            return read(connection -> {
                verifySchema(connection);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check")) {
                    if (!rows.next() || !"ok".equals(rows.getString(1))) return false;
                }
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                    return !rows.next();
                }
            });
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void verifySchema(Connection connection) throws SQLException {
        if (schemaVersion(connection) != SCHEMA_VERSION) throw new SQLException("Unsupported schema");
        try (var statement = connection.prepareStatement("SELECT value FROM settings WHERE key='start_date'");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !properties.startDate().toString().equals(rows.getString(1))) {
                throw new SQLException("Start date mismatch");
            }
        }
        // Resolve every required column before declaring readiness, including an empty database.
        execute(connection, "SELECT id,owner,type,effective_at,created_at,updated_at,version FROM events LIMIT 0");
        execute(connection, "SELECT owner,date,confirmed_at FROM zero_declarations LIMIT 0");
        execute(connection, "SELECT owner,submission_key,payload,event_id FROM submissions LIMIT 0");
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            boolean result = statement.execute();
            return result ? 0 : statement.getUpdateCount();
        }
    }
}
