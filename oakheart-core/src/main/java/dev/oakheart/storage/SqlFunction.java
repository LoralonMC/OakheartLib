package dev.oakheart.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of work against a {@link SqliteStore} connection. */
@FunctionalInterface
public interface SqlFunction<T> {
    T apply(Connection connection) throws SQLException;
}
