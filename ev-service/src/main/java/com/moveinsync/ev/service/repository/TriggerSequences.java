package com.moveinsync.ev.service.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Next trigger sequence for one tenant and producer. The counter commits with the job. */
public final class TriggerSequences {

  public long next(Connection connection, String tenant, String producer) throws SQLException {
    String sql =
        """
        INSERT INTO trigger_seq (tenant_id, producer, next_seq) VALUES (?, ?, 1)
        ON CONFLICT (tenant_id, producer)
        DO UPDATE SET next_seq = trigger_seq.next_seq + 1
        RETURNING next_seq
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, producer);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("trigger sequence was not allocated");
        }
        return rows.getLong(1);
      }
    }
  }
}
