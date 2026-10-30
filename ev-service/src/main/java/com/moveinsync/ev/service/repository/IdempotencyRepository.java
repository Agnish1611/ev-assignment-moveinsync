package com.moveinsync.ev.service.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Stores the response of one successful call so a repeated key replays it. */
public final class IdempotencyRepository {

  public Optional<StoredCall> find(Connection connection, String userId, String key)
      throws SQLException {
    String sql =
        "SELECT method, path, status_code, response_body FROM idempotent_call"
            + " WHERE user_id = ? AND idempotency_key = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, userId);
      statement.setString(2, key);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new StoredCall(
                rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4)));
      }
    }
  }

  public void save(
      Connection connection,
      String tenant,
      String userId,
      String key,
      String method,
      String path,
      int status,
      String body,
      Instant createdAt)
      throws SQLException {
    String sql =
        "INSERT INTO idempotent_call"
            + " (tenant_id, user_id, idempotency_key, method, path, status_code, response_body,"
            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, userId);
      statement.setString(3, key);
      statement.setString(4, method);
      statement.setString(5, path);
      statement.setInt(6, status);
      statement.setString(7, body);
      statement.setTimestamp(8, Timestamp.from(createdAt));
      statement.executeUpdate();
    }
  }

  public record StoredCall(String method, String path, int status, String body) {}
}
