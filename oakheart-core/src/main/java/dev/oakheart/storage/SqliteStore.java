package dev.oakheart.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SQLite database with one writer thread and one synchronous reader, over the
 * {@code org.sqlite} driver Paper bundles (declare nothing; never shade a driver).
 *
 * <ul>
 *   <li><b>Writes</b> ({@link #write}, {@link #transaction}, {@link #readAfterWrites}) run in
 *       order on a {@link SerialWriter}, on a connection only that thread uses. Their futures
 *       complete on the writer thread.</li>
 *   <li><b>Reads</b> ({@link #read}) run on the calling thread, the main thread included, on a
 *       separate query-only connection behind its own lock. They do not see writes still queued.
 *       Keep them small and bounded.</li>
 *   <li><b>Schema versions</b> live in {@code PRAGMA user_version}. On open a newer version than
 *       {@link #schemaVersion()} refuses to open (the plugin fails to enable rather than write to
 *       a schema it does not know); an older one migrates in one transaction.</li>
 *   <li><b>Journal mode</b> is WAL. File-level backups and copies of a WAL database must include
 *       the {@code -wal} and {@code -shm} files, or use SQLite's backup API.</li>
 * </ul>
 *
 * <p>Pre-adoption databases that kept their version in a table of their own bridge through
 * {@link #legacyVersion} (read when {@code user_version} is 0) and {@link #mirrorLegacyVersion}
 * (kept in step after each migration, so a pre-adoption jar reads the right version). Rolling a
 * plugin back below a schema bump is unsupported.</p>
 */
public abstract class SqliteStore {

    private static final String DRIVER = "org.sqlite.JDBC";

    private final Path path;
    private final Logger logger;
    private final boolean itemCritical;
    private final SerialWriter writer;

    /** Owned by the writer thread; touched elsewhere only after the writer has drained. */
    private Connection writerConnection;
    private Connection readerConnection;
    private final Object readLock = new Object();
    private volatile boolean opened;
    private volatile boolean closed;

    /**
     * @param path         the database file; parent directories are created on open
     * @param threadName   the writer thread's name, e.g. {@code "OakLog-db-writer"}
     * @param itemCritical true for a database whose rows stand for items or currency (a write lost
     *                     to power failure is a loss or a dupe): {@code synchronous=FULL}.
     *                     Otherwise {@code NORMAL}, which in WAL mode can lose the last commits
     *                     on power failure but never corrupts.
     */
    protected SqliteStore(Path path, String threadName, Logger logger, boolean itemCritical) {
        this.path = Objects.requireNonNull(path, "path");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.itemCritical = itemCritical;
        this.writer = new SerialWriter(threadName, logger);
    }

    /** The schema version this build writes. At least 1. */
    protected abstract int schemaVersion();

    /**
     * Brings the schema from version {@code from} (0 for a new database) to
     * {@link #schemaVersion()}. Runs on the writer thread inside the open transaction; throw to
     * roll everything back and fail the open.
     *
     * <p>Pragmas that are no-ops inside a transaction ({@code foreign_keys}) belong in
     * {@link #configureConnection}; a migration needing {@code VACUUM} must run it after open,
     * through {@link #readAfterWrites}.</p>
     */
    protected abstract void migrate(Connection connection, int from) throws SQLException;

    /**
     * The version of a database that predates {@code user_version}, read when it is 0. Return 0
     * when the legacy table is missing (a new database). Default 0.
     */
    protected int legacyVersion(Connection connection) throws SQLException {
        return 0;
    }

    /**
     * Writes {@code version} into the legacy version table, inside the migration transaction.
     * Default no-op.
     */
    protected void mirrorLegacyVersion(Connection connection, int version) throws SQLException {
    }

    /**
     * Per-connection settings, run on both connections outside any transaction, after the
     * library's pragmas (e.g. {@code PRAGMA foreign_keys = ON}). Default no-op.
     *
     * @param writer true for the writer connection (on the writer thread), false for the reader
     */
    protected void configureConnection(Connection connection, boolean writer) throws SQLException {
    }

    /**
     * Opens both connections and migrates. Blocks the calling thread (call it in
     * {@code onEnable}).
     *
     * @throws IOException if the driver is missing, the file cannot be opened, the database's
     *                     schema is newer than this build's, or the migration fails (rolled back)
     */
    public final void open() throws IOException {
        if (opened) {
            throw new IllegalStateException("SqliteStore " + path + " is already open");
        }
        opened = true;
        int target = schemaVersion();
        if (target < 1) {
            throw new IllegalStateException("schemaVersion() must be at least 1, was " + target);
        }
        try {
            Class.forName(DRIVER);
        } catch (ClassNotFoundException e) {
            throw new IOException("The SQLite driver (" + DRIVER + ") is not on the classpath", e);
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            writer.supply(() -> {
                openWriter(target);
                return null;
            }).get();
        } catch (ExecutionException e) {
            stopAfterFailedOpen();
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io
                    : new IOException("Could not open " + path + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopAfterFailedOpen();
            throw new IOException("Interrupted while opening " + path, e);
        }
        try {
            Connection reader = DriverManager.getConnection(url());
            try (Statement st = reader.createStatement()) {
                st.execute("PRAGMA busy_timeout = 1000");
                st.execute("PRAGMA query_only = 1");
            }
            configureConnection(reader, false);
            synchronized (readLock) {
                readerConnection = reader;
            }
        } catch (SQLException e) {
            close(Duration.ofSeconds(5));
            throw new IOException("Could not open the reader connection to " + path, e);
        }
    }

    /** A failed open leaves no connection; stop the writer thread so it does not linger. */
    private void stopAfterFailedOpen() {
        closed = true;
        writer.drain(Duration.ofSeconds(5));
    }

    private void openWriter(int target) throws IOException {
        Connection c;
        try {
            c = DriverManager.getConnection(url());
        } catch (SQLException e) {
            throw new IOException("Could not open " + path, e);
        }
        try {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
                    String mode = rs.next() ? rs.getString(1) : "?";
                    if (!"wal".equalsIgnoreCase(mode)) {
                        logger.warning(path.getFileName() + " stayed in journal mode " + mode
                                + " instead of WAL");
                    }
                }
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA synchronous = " + (itemCritical ? "FULL" : "NORMAL"));
            }
            configureConnection(c, true);

            int stored = userVersion(c);
            int current = stored != 0 ? stored : legacyVersion(c);
            if (current > target) {
                throw new IOException(path.getFileName() + " has schema version " + current
                        + ", newer than this build's " + target + ". A newer build wrote it; "
                        + "refusing to open rather than write to an unknown schema.");
            }
            if (current < target || stored == 0) {
                c.setAutoCommit(false);
                try {
                    if (current < target) {
                        migrate(c, current);
                    }
                    try (Statement st = c.createStatement()) {
                        st.execute("PRAGMA user_version = " + target);
                    }
                    if (current < target) {
                        mirrorLegacyVersion(c, target);
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    try {
                        c.rollback();
                    } catch (SQLException rollback) {
                        e.addSuppressed(rollback);
                    }
                    throw new IOException("Migrating " + path.getFileName() + " from version "
                            + current + " to " + target + " failed and was rolled back", e);
                } finally {
                    c.setAutoCommit(true);
                }
                if (current < target) {
                    logger.info("Migrated " + path.getFileName() + " from schema version "
                            + current + " to " + target);
                }
            }
        } catch (IOException | SQLException | RuntimeException e) {
            closeQuietly(c);
            throw e instanceof IOException io ? io : new IOException("Could not open " + path, e);
        }
        writerConnection = c;
    }

    /** Runs one statement on the writer; completes with the update count. */
    public CompletableFuture<Integer> write(String sql, Object... params) {
        return writer.supply(() -> {
            try (PreparedStatement ps = writer().prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** Runs {@code work} on the writer in one transaction, rolled back if it throws. */
    public <T> CompletableFuture<T> transaction(SqlFunction<T> work) {
        return writer.supply(() -> {
            Connection c = writer();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (Exception | Error e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    /** Runs {@code work} on the writer connection, after every write queued before it. */
    public <T> CompletableFuture<T> readAfterWrites(SqlFunction<T> work) {
        return writer.supply(() -> work.apply(writer()));
    }

    /**
     * Runs {@code work} synchronously on the reader connection. Allowed on the main thread for
     * small, bounded queries. Does not see writes still queued on the writer.
     */
    public <T> T read(SqlFunction<T> work) throws SQLException {
        synchronized (readLock) {
            if (readerConnection == null) {
                throw new SQLException("SqliteStore " + path.getFileName()
                        + (closed ? " is closed" : " is not open"));
            }
            return work.apply(readerConnection);
        }
    }

    /** Equivalent to {@code close(Duration.ofSeconds(10))}. */
    public void close() {
        close(Duration.ofSeconds(10));
    }

    /**
     * Drains the writer, then closes both connections. If the drain times out, the abandoned
     * writes are logged and the writer connection is left to the JVM exit, since a task may still
     * be using it. Call in {@code onDisable}, last.
     */
    public void close(Duration timeout) {
        if (closed) {
            return;
        }
        closed = true;
        boolean drained = writer.drain(timeout);
        synchronized (readLock) {
            closeQuietly(readerConnection);
            readerConnection = null;
        }
        if (drained) {
            closeQuietly(writerConnection);
            writerConnection = null;
        } else {
            logger.severe("SqliteStore " + path.getFileName() + ": the writer did not drain; its "
                    + "connection is left open until the server stops.");
        }
    }

    /** The database file. */
    public Path path() {
        return path;
    }

    private Connection writer() throws SQLException {
        if (writerConnection == null) {
            throw new SQLException("SqliteStore " + path.getFileName() + " is not open");
        }
        return writerConnection;
    }

    private String url() {
        return "jdbc:sqlite:" + path.toAbsolutePath();
    }

    private static int userVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Closing a connection to " + path.getFileName() + " failed", e);
        }
    }
}
