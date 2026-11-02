package dev.oakheart.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStoreTest {

    private static final Logger LOG = Logger.getLogger("SqliteStoreTest");

    @TempDir
    Path dir;

    /** Schema 1: table a. Schema 2: adds column b. Optional legacy table {@code schema_version}. */
    private static class TestStore extends SqliteStore {
        final int version;
        final boolean legacy;
        final List<Integer> migratedFrom = new ArrayList<>();
        boolean failMigration;

        TestStore(Path path, int version, boolean legacy) {
            super(path, "test-db-writer", LOG, true);
            this.version = version;
            this.legacy = legacy;
        }

        @Override
        protected int schemaVersion() {
            return version;
        }

        @Override
        protected void migrate(Connection c, int from) throws SQLException {
            migratedFrom.add(from);
            try (Statement st = c.createStatement()) {
                if (from < 1) {
                    st.execute("CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT)");
                }
                if (from < 2 && version >= 2) {
                    st.execute("ALTER TABLE a ADD COLUMN b INTEGER DEFAULT 0");
                }
            }
            if (failMigration) {
                throw new SQLException("simulated migration failure");
            }
        }

        @Override
        protected int legacyVersion(Connection c) throws SQLException {
            if (!legacy) {
                return 0;
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                if (!rs.next()) {
                    return 0;
                }
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }

        @Override
        protected void mirrorLegacyVersion(Connection c, int v) throws SQLException {
            if (legacy) {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER)");
                    st.execute("DELETE FROM schema_version");
                    st.execute("INSERT INTO schema_version VALUES (" + v + ")");
                }
            }
        }
    }

    private Path db() {
        return dir.resolve("sub").resolve("test.db");
    }

    private static int pragma(Path db, String pragma) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
            return rs.getInt(1);
        }
    }

    private static void exec(Path db, String... sql) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            for (String s : sql) {
                st.execute(s);
            }
        }
    }

    @Test
    void newDatabaseMigratesFromZeroAndSetsUserVersion() throws Exception {
        TestStore store = new TestStore(db(), 2, false);
        store.open();
        assertEquals(List.of(0), store.migratedFrom);
        store.close();
        assertEquals(2, pragma(db(), "user_version"));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
            assertEquals("wal", rs.getString(1));
        }
    }

    @Test
    void reopenAtTheSameVersionDoesNotMigrate() throws Exception {
        TestStore first = new TestStore(db(), 2, false);
        first.open();
        first.close();
        TestStore second = new TestStore(db(), 2, false);
        second.open();
        assertTrue(second.migratedFrom.isEmpty());
        second.close();
    }

    @Test
    void olderVersionMigratesFromIt() throws Exception {
        TestStore v1 = new TestStore(db(), 1, false);
        v1.open();
        v1.close();
        TestStore v2 = new TestStore(db(), 2, false);
        v2.open();
        assertEquals(List.of(1), v2.migratedFrom);
        v2.close();
        assertEquals(2, pragma(db(), "user_version"));
    }

    @Test
    void newerVersionRefusesToOpen() throws Exception {
        TestStore v2 = new TestStore(db(), 2, false);
        v2.open();
        v2.close();
        TestStore v1 = new TestStore(db(), 1, false);
        IOException e = assertThrows(IOException.class, v1::open);
        assertTrue(e.getMessage().contains("newer than this build's 1"), e.getMessage());
        assertTrue(v1.migratedFrom.isEmpty());
        assertEquals(2, pragma(db(), "user_version"));
    }

    @Test
    void legacyVersionBridgesAndIsMirrored() throws Exception {
        java.nio.file.Files.createDirectories(db().getParent());
        exec(db(), "CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE schema_version (version INTEGER)", "INSERT INTO schema_version VALUES (1)");
        TestStore store = new TestStore(db(), 2, true);
        store.open();
        assertEquals(List.of(1), store.migratedFrom);
        int mirrored = store.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                return rs.getInt(1);
            }
        });
        assertEquals(2, mirrored);
        store.close();
        assertEquals(2, pragma(db(), "user_version"));
    }

    @Test
    void legacyAtCurrentVersionOnlyStampsUserVersion() throws Exception {
        java.nio.file.Files.createDirectories(db().getParent());
        exec(db(), "CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT, b INTEGER)",
                "CREATE TABLE schema_version (version INTEGER)", "INSERT INTO schema_version VALUES (2)");
        TestStore store = new TestStore(db(), 2, true);
        store.open();
        assertTrue(store.migratedFrom.isEmpty());
        store.close();
        assertEquals(2, pragma(db(), "user_version"));
    }

    @Test
    void failedMigrationRollsBackEverything() throws Exception {
        TestStore v1 = new TestStore(db(), 1, false);
        v1.open();
        v1.close();
        TestStore v2 = new TestStore(db(), 2, false);
        v2.failMigration = true;
        assertThrows(IOException.class, v2::open);
        assertEquals(1, pragma(db(), "user_version"));
        // The ALTER TABLE inside the failed migration was rolled back too.
        TestStore again = new TestStore(db(), 2, false);
        again.open();
        assertEquals(List.of(1), again.migratedFrom);
        again.close();
    }

    @Test
    void writesAreOrderedAndReadAfterWritesSeesThem() throws Exception {
        TestStore store = new TestStore(db(), 2, false);
        store.open();
        for (int i = 0; i < 100; i++) {
            store.write("INSERT INTO a (id, name) VALUES (?, ?)", i, "n" + i);
        }
        store.write("UPDATE a SET name = ? WHERE id = ?", "last", 99);
        int count = store.readAfterWrites(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM a")) {
                return rs.getInt(1);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(100, count);
        String name = store.read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name FROM a WHERE id = 99")) {
                return rs.getString(1);
            }
        });
        assertEquals("last", name);
        store.close();
    }

    @Test
    void transactionRollsBackOnThrow() throws Exception {
        TestStore store = new TestStore(db(), 2, false);
        store.open();
        var failed = store.transaction(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO a (id, name) VALUES (1, 'x')");
            }
            throw new SQLException("abort");
        });
        assertThrows(CompletionException.class, () -> failed.orTimeout(5, TimeUnit.SECONDS).join());
        int count = store.readAfterWrites(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM a")) {
                return rs.getInt(1);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(0, count);
        // The connection is back in auto-commit: a plain write after the rollback persists.
        assertEquals(1, store.write("INSERT INTO a (id, name) VALUES (2, 'y')").get(5, TimeUnit.SECONDS));
        store.close();
    }

    @Test
    void readerIsQueryOnly() throws Exception {
        TestStore store = new TestStore(db(), 2, false);
        store.open();
        assertThrows(SQLException.class, () -> store.read(c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate("INSERT INTO a (id, name) VALUES (5, 'z')");
            }
        }));
        store.close();
    }

    @Test
    void afterCloseWritesFailAndReadsThrow() throws Exception {
        TestStore store = new TestStore(db(), 2, false);
        store.open();
        store.close();
        CompletionException e = assertThrows(CompletionException.class,
                () -> store.write("INSERT INTO a (id) VALUES (1)").join());
        assertInstanceOf(RejectedExecutionException.class, e.getCause());
        assertThrows(SQLException.class, () -> store.read(c -> 1));
        store.close(); // idempotent
    }
}
