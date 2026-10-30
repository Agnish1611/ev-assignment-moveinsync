package com.moveinsync.ev.service.plan;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Puts the transaction on the non-owner role and sets the tenant for row-level security. */
public final class TenantSession {

  private TenantSession() {
    throw new IllegalStateException("utility");
  }

  public static void apply(Connection connection, String tenantId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL ROLE ev_app");
    }
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant', ?, true)")) {
      statement.setString(1, tenantId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("app.tenant was not set");
        }
      }
    }
  }
}
