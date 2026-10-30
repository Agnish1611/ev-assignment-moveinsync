package com.moveinsync.ev.service.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Records an accepted planning job. It does not publish a plan head. */
public final class JobRepository {

  public void store(
      Connection connection, String tenant, String jobId, String shard, Instant createdAt)
      throws SQLException {
    String sql =
        "INSERT INTO plan_job (tenant_id, job_id, shard, status, created_at) VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, jobId);
      statement.setString(3, shard);
      statement.setString(4, "ACCEPTED");
      statement.setTimestamp(5, Timestamp.from(createdAt));
      statement.executeUpdate();
    }
  }
}
