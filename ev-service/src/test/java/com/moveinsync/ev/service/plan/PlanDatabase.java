package com.moveinsync.ev.service.plan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One Postgres database for the publication tests. The container stops when the JVM exits. */
final class PlanDatabase {

  private static PostgreSQLContainer postgres;
  private static PGSimpleDataSource dataSource;

  private PlanDatabase() {
    throw new IllegalStateException("utility");
  }

  static synchronized DataSource open() {
    if (postgres != null) {
      return dataSource;
    }
    PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
    container.start();
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(container.getJdbcUrl());
    source.setUser(container.getUsername());
    source.setPassword(container.getPassword());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    grant(source);
    postgres = container;
    dataSource = source;
    return source;
  }

  static long count(String sql, Object... args) {
    try (Connection connection = open().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return 0L;
        }
        return rows.getLong(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("query failed", ex);
    }
  }

  static String text(String sql, Object... args) {
    try (Connection connection = open().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return "";
        }
        return rows.getString(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("query failed", ex);
    }
  }

  static byte[] bytes(String sql, Object... args) {
    try (Connection connection = open().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return new byte[0];
        }
        return rows.getBytes(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("query failed", ex);
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int index = 0; index < args.length; index++) {
      statement.setObject(index + 1, args[index]);
    }
  }

  private static void grant(DataSource source) {
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("GRANT ev_app TO CURRENT_USER");
      statement.execute("GRANT ev_relay TO CURRENT_USER");
    } catch (SQLException ex) {
      throw new IllegalStateException("ev_app was not granted", ex);
    }
  }
}
