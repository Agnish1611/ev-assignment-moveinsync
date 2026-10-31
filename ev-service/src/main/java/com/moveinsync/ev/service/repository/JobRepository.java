package com.moveinsync.ev.service.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Planning jobs. A job is not a published head. */
public final class JobRepository {

  private static final Set<String> TERMINAL =
      Set.of("PUBLISHED", "REJECTED", "INFEASIBLE", "FAILED", "STALE_INPUT");

  public void store(
      Connection connection,
      String tenant,
      String jobId,
      String shard,
      Instant createdAt,
      String mode)
      throws SQLException {
    String sql =
        "INSERT INTO plan_job (tenant_id, job_id, shard, status, created_at, code, mode)"
            + " VALUES (?, ?, ?, 'ACCEPTED', ?, '', ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, jobId);
      statement.setString(3, shard);
      statement.setTimestamp(4, Timestamp.from(createdAt));
      statement.setString(5, mode);
      statement.executeUpdate();
    }
  }

  public Optional<JobView> find(Connection connection, String tenant, String jobId)
      throws SQLException {
    String sql =
        "SELECT job_id, shard, status, code, published_version, mode FROM plan_job"
            + " WHERE tenant_id = ? AND job_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, jobId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(read(rows));
      }
    }
  }

  public int claim(Connection connection, String tenant, String jobId, Instant until)
      throws SQLException {
    String sql =
        "UPDATE plan_job SET status = 'RUNNING', lease_until = ?"
            + " WHERE tenant_id = ? AND job_id = ? AND status = 'ACCEPTED'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(until));
      statement.setString(2, tenant);
      statement.setString(3, jobId);
      return statement.executeUpdate();
    }
  }

  public int finish(
      Connection connection,
      String tenant,
      String jobId,
      String status,
      String code,
      long publishedVersion)
      throws SQLException {
    if (!TERMINAL.contains(status)) {
      throw new IllegalArgumentException("status is not terminal");
    }
    String sql =
        "UPDATE plan_job SET status = ?, code = ?, published_version = ?, lease_until = NULL"
            + " WHERE tenant_id = ? AND job_id = ? AND status = 'RUNNING'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, status);
      statement.setString(2, code);
      if (publishedVersion <= 0L) {
        statement.setNull(3, Types.BIGINT);
      } else {
        statement.setLong(3, publishedVersion);
      }
      statement.setString(4, tenant);
      statement.setString(5, jobId);
      return statement.executeUpdate();
    }
  }

  public int release(Connection connection, String tenant, String jobId) throws SQLException {
    String sql =
        "UPDATE plan_job SET status = 'ACCEPTED', lease_until = NULL"
            + " WHERE tenant_id = ? AND job_id = ? AND status = 'RUNNING'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, jobId);
      return statement.executeUpdate();
    }
  }

  public int abandon(Connection connection, String tenant, String jobId, String status, String code)
      throws SQLException {
    if (!TERMINAL.contains(status)) {
      throw new IllegalArgumentException("status is not terminal");
    }
    String sql =
        "UPDATE plan_job SET status = ?, code = ?, published_version = NULL, lease_until = NULL"
            + " WHERE tenant_id = ? AND job_id = ? AND status IN ('ACCEPTED', 'RUNNING')";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, status);
      statement.setString(2, code);
      statement.setString(3, tenant);
      statement.setString(4, jobId);
      return statement.executeUpdate();
    }
  }

  public int reap(Connection connection, Instant now) throws SQLException {
    String sql =
        "UPDATE plan_job SET status = 'ACCEPTED', lease_until = NULL"
            + " WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(now));
      return statement.executeUpdate();
    }
  }

  private static JobView read(ResultSet rows) throws SQLException {
    Long published = rows.getObject("published_version", Long.class);
    return new JobView(
        rows.getString("job_id"),
        rows.getString("shard"),
        rows.getString("status"),
        rows.getString("code"),
        published == null ? 0L : published,
        rows.getString("mode"));
  }

  /** One job. {@code publishedVersion} is 0 until a head is stored. */
  public record JobView(
      String jobId, String shard, String status, String code, long publishedVersion, String mode) {}
}
