package com.moveinsync.ev.service.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Claims unpublished outbox rows. The caller deletes a row only after the bus accepts it. */
public final class OutboxRepository {

  private static final String CLAIM =
      """
      SELECT outbox_id, tenant_id, shard, version, created_at, topic, payload
      FROM outbox
      ORDER BY outbox_id
      LIMIT ?
      FOR UPDATE SKIP LOCKED
      """;

  public List<OutboxRow> claim(Connection connection, int limit) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(CLAIM)) {
      statement.setInt(1, limit);
      try (ResultSet rows = statement.executeQuery()) {
        List<OutboxRow> claimed = new ArrayList<>();
        while (rows.next()) {
          claimed.add(read(rows));
        }
        return List.copyOf(claimed);
      }
    }
  }

  public void delete(Connection connection, String tenant, long id) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM outbox WHERE tenant_id = ? AND outbox_id = ?")) {
      statement.setString(1, tenant);
      statement.setLong(2, id);
      statement.executeUpdate();
    }
  }

  private static OutboxRow read(ResultSet rows) throws SQLException {
    Timestamp created = rows.getTimestamp("created_at");
    String topic = rows.getString("topic");
    String payload = rows.getString("payload");
    if (created == null || topic == null || payload == null) {
      throw new SQLException("outbox row is incomplete");
    }
    return new OutboxRow(
        rows.getLong("outbox_id"),
        rows.getString("tenant_id"),
        rows.getString("shard"),
        rows.getLong("version"),
        created.toInstant(),
        topic,
        payload);
  }

  /** One unpublished row. A plan event carries version. A trigger carries the envelope. */
  public record OutboxRow(
      long id,
      String tenant,
      String shard,
      long version,
      Instant createdAt,
      String topic,
      String payload) {}
}
