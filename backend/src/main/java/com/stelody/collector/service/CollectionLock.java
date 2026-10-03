package com.stelody.collector.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public final class CollectionLock implements AutoCloseable {
  private static final long KEY = 73152841062017L;
  private final Connection connection;

  private CollectionLock(Connection connection) {
    this.connection = connection;
  }

  public static CollectionLock acquire(DataSource source) throws SQLException {
    Connection connection = source.getConnection();
    try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, KEY);
      statement.setQueryTimeout(5);
      try (var rows = statement.executeQuery()) {
        if (rows.next() && rows.getBoolean(1)) return new CollectionLock(connection);
      }
      connection.close();
      return null;
    } catch (SQLException exception) {
      connection.close();
      throw exception;
    }
  }

  @Override
  public void close() throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      statement.setLong(1, KEY);
      statement.setQueryTimeout(5);
      statement.execute();
    } finally {
      connection.close();
    }
  }

  public void check() throws SQLException {
    try (var statement = connection.prepareStatement("SELECT 1")) {
      statement.setQueryTimeout(5);
      statement.executeQuery().close();
    }
  }
}
