package com.moveinsync.ev.service.plan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reads the outbox as {@code ev_relay}. An empty tenant makes the tenant policy match nothing, so
 * the relay policy is what returns rows.
 */
public final class RelaySession {

  private RelaySession() {
    throw new IllegalStateException("utility");
  }

  public static void apply(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL ROLE ev_relay");
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant', '', true)")) {
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("app.tenant was not set");
        }
      }
    }
  }
}
