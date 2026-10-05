import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.sqlite.SQLiteConfig;

// Source-file launcher, Java 25 and the application's approved SQLite driver.
// No database contents, paths, exception text or derived fingerprints are logged.
class RageQuitDb {
    static void require(boolean value) { if (!value) throw new IllegalArgumentException(); }
    static Connection open(Path path, boolean readOnly) throws Exception {
        require(path.isAbsolute() && !Files.isSymbolicLink(path));
        if (readOnly) require(Files.isRegularFile(path));
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(2000);
        config.setReadOnly(readOnly);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }
    static void execute(Connection c, String sql) throws Exception {
        try (var statement = c.createStatement()) { statement.execute(sql); }
    }
    static String scalar(Connection c, String sql) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            require(r.next()); String value = r.getString(1); require(!r.next()); return value;
        }
    }
    static List<List<String>> rows(Connection c, String sql) throws Exception {
        var result = new ArrayList<List<String>>();
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            while (r.next()) {
                var row = new ArrayList<String>();
                for (int n = 1; n <= r.getMetaData().getColumnCount(); n++) row.add(r.getString(n));
                result.add(row);
            }
        }
        return result;
    }
    static void schema(Connection c, Path migration) throws Exception {
        for (String sql : Files.readString(migration).split(";")) if (!sql.isBlank()) execute(c, sql);
    }
    static List<List<String>> schemaRows(Connection c) throws Exception {
        var definitions = rows(c, "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name");
        for (var row : definitions) if (row.get(3) != null) row.set(3, row.get(3).replaceAll("\\s+", " ").trim());
        return definitions;
    }
    static void validate(Connection c, Path migration, String date) throws Exception {
        require(LocalDate.parse(date).toString().equals(date));
        require(!LocalDate.parse(date).isAfter(LocalDate.now(ZoneId.of("Europe/Rome"))));
        require("1".equals(scalar(c, "PRAGMA user_version")));
        require("delete".equalsIgnoreCase(scalar(c, "PRAGMA journal_mode")));
        require("ok".equals(scalar(c, "PRAGMA integrity_check")));
        require(rows(c, "PRAGMA foreign_key_check").isEmpty());
        require(rows(c, "SELECT key,value FROM settings ORDER BY key").equals(List.of(List.of("start_date", date))));
        try (var expected = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            schema(expected, migration); require(schemaRows(c).equals(schemaRows(expected)));
        }
        require("0".equals(scalar(c, "SELECT count(*) FROM events WHERE owner NOT IN ('user:admin','user:moody','user:frevadiscor') OR type NOT IN ('SMOKED','RESISTED') OR version < 1")));
        require("0".equals(scalar(c, "SELECT count(*) FROM zero_declarations WHERE owner NOT IN ('user:admin','user:moody','user:frevadiscor')")));
        LocalDate start = LocalDate.parse(date);
        for (var row : rows(c, "SELECT effective_at,created_at,updated_at FROM events")) {
            Instant effective = Instant.parse(row.get(0));
            require(!effective.atZone(ZoneId.of("Europe/Rome")).toLocalDate().isBefore(start));
            require(!effective.isAfter(Instant.now()));
            Instant.parse(row.get(1)); Instant.parse(row.get(2));
        }
        for (var row : rows(c, "SELECT date,confirmed_at FROM zero_declarations")) {
            require(!LocalDate.parse(row.get(0)).isBefore(start));
            require(!LocalDate.parse(row.get(0)).isAfter(LocalDate.now(ZoneId.of("Europe/Rome"))));
            Instant.parse(row.get(1));
        }
    }
    static List<List<List<String>>> state(Connection c) throws Exception {
        return List.of(rows(c, "SELECT * FROM settings ORDER BY key"), rows(c, "SELECT * FROM events ORDER BY id"),
                rows(c, "SELECT * FROM zero_declarations ORDER BY owner,date"), rows(c, "SELECT * FROM submissions ORDER BY owner,submission_key"));
    }
    static void fixture(Path path, Path migration, String date) throws Exception {
        require(!Files.exists(path));
        try (var c = open(path, false)) {
            execute(c, "PRAGMA journal_mode=DELETE"); execute(c, "PRAGMA synchronous=FULL"); schema(c, migration);
            try (var s = c.prepareStatement("INSERT INTO settings VALUES('start_date',?)")) { s.setString(1, date); s.executeUpdate(); }
            execute(c, "PRAGMA user_version=1");
            String first = LocalDate.parse(date).atTime(10,0).toInstant(ZoneOffset.UTC).toString();
            String second = LocalDate.parse(date).atTime(12,0).toInstant(ZoneOffset.UTC).toString();
            try (var s = c.prepareStatement("INSERT INTO events VALUES(?,'user:admin',?,?,?, ?,1)")) {
                for (int i = 1; i <= 3; i++) {
                    s.setString(1, "synthetic-" + i); s.setString(2, i == 3 ? "RESISTED" : "SMOKED");
                    s.setString(3, i == 1 ? first : second); s.setString(4, first); s.setString(5, first); s.executeUpdate();
                }
            }
            try (var s = c.prepareStatement("INSERT INTO zero_declarations VALUES('user:moody',?,?)")) {
                s.setString(1, date); s.setString(2, first); s.executeUpdate();
            }
            execute(c, "INSERT INTO submissions VALUES('user:admin','synthetic-key','synthetic-payload','synthetic-1')");
            validate(c, migration, date);
        }
    }
    public static void main(String[] args) {
        try {
            require(args.length >= 4);
            String command = args[0]; Path db = Path.of(args[1]), migration = Path.of(args[2]); String date = args[3];
            if (command.equals("fixture")) { fixture(db, migration, date); return; }
            try (var c = open(db, true)) {
                validate(c, migration, date);
                if (command.equals("compare")) {
                    require(args.length == 5);
                    try (var other = open(Path.of(args[4]), true)) {
                        validate(other, migration, date); require(state(c).equals(state(other)));
                        // Identical inputs preserve derived daily/cumulative scores and adjacent-cigarette gaps.
                        require(rows(c, "SELECT owner,type,count(*) FROM events GROUP BY owner,type ORDER BY owner,type")
                                .equals(rows(other, "SELECT owner,type,count(*) FROM events GROUP BY owner,type ORDER BY owner,type")));
                        require(rows(c, "SELECT effective_at FROM events WHERE type='SMOKED' ORDER BY owner,effective_at,id")
                                .equals(rows(other, "SELECT effective_at FROM events WHERE type='SMOKED' ORDER BY owner,effective_at,id")));
                    }
                } else require(command.equals("validate"));
            }
        } catch (Throwable failure) { System.err.println("Database verification failed (details suppressed)."); System.exit(1); }
    }
}
