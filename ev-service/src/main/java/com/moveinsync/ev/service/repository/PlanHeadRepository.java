package com.moveinsync.ev.service.repository;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Compare-and-set updates of the plan head and the rows stored in the same transaction. */
public final class PlanHeadRepository {

  private static final String UNIQUE_VIOLATION = "23505";

  public void ensureTenant(Connection connection, String tenant) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO tenant (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING")) {
      statement.setString(1, tenant);
      statement.executeUpdate();
    }
  }

  public OptionalLong processedVersion(
      Connection connection, String tenant, String producer, long sequence) throws SQLException {
    String sql =
        "SELECT version FROM processed_event WHERE tenant_id = ? AND producer = ? AND seq = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, producer);
      statement.setLong(3, sequence);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return OptionalLong.empty();
        }
        return OptionalLong.of(rows.getLong(1));
      }
    }
  }

  public void insertProcessed(
      Connection connection,
      String tenant,
      String producer,
      long sequence,
      String shard,
      long version)
      throws SQLException {
    String sql =
        "INSERT INTO processed_event (tenant_id, producer, seq, shard, version) VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, producer);
      statement.setLong(3, sequence);
      statement.setString(4, shard);
      statement.setLong(5, version);
      statement.executeUpdate();
    }
  }

  /**
   * Advances the head when it still has {@code expected}. An empty result means another writer won.
   * The first head uses expected 0 and becomes version 1.
   */
  public OptionalLong claim(
      Connection connection, String tenant, String shard, long expected, Instant at)
      throws SQLException {
    OptionalLong current = lockHead(connection, tenant, shard);
    if (current.isEmpty()) {
      if (expected != 0L) {
        return OptionalLong.empty();
      }
      return insertHead(connection, tenant, shard, at);
    }
    if (current.orElseThrow() != expected) {
      return OptionalLong.empty();
    }
    if (updateHead(connection, tenant, shard, expected, at) != 1) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(expected + 1L);
  }

  public OptionalLong headVersion(Connection connection, String tenant, String shard)
      throws SQLException {
    return lockHead(connection, tenant, shard);
  }

  public Optional<Instant> latestSnapshotAt(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql =
        "SELECT created_at FROM plan_snapshot WHERE tenant_id = ? AND shard = ?"
            + " ORDER BY version DESC LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(rows.getTimestamp(1).toInstant());
      }
    }
  }

  public void insertVersion(
      Connection connection,
      String tenant,
      String shard,
      long version,
      OptionalLong parent,
      FleetPlan plan,
      String objective,
      String bounds,
      String report,
      Instant at)
      throws SQLException {
    String sql =
        "INSERT INTO plan_version (tenant_id, shard, version, parent_version, input_hash, seed,"
            + " objective_json, bounds_json, validator_report, plan_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      if (parent.isPresent()) {
        statement.setLong(4, parent.orElseThrow());
      } else {
        statement.setNull(4, Types.BIGINT);
      }
      statement.setString(5, plan.inputHash());
      statement.setLong(6, plan.seed());
      statement.setString(7, objective);
      statement.setString(8, bounds);
      statement.setString(9, report);
      statement.setString(10, plan.planId());
      statement.setTimestamp(11, Timestamp.from(at));
      statement.executeUpdate();
    }
  }

  public void insertDiff(
      Connection connection, String tenant, String shard, long version, String changes)
      throws SQLException {
    String sql = "INSERT INTO plan_diff (tenant_id, shard, version, changes) VALUES (?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setString(4, changes);
      statement.executeUpdate();
    }
  }

  public void insertSnapshot(
      Connection connection,
      String tenant,
      String shard,
      long version,
      byte[] compressed,
      Instant at)
      throws SQLException {
    String sql =
        "INSERT INTO plan_snapshot (tenant_id, shard, version, compressed_plan, created_at)"
            + " VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setBytes(4, compressed);
      statement.setTimestamp(5, Timestamp.from(at));
      statement.executeUpdate();
    }
  }

  public void insertOutbox(
      Connection connection,
      String tenant,
      String shard,
      long version,
      String payload,
      Instant at,
      String topic)
      throws SQLException {
    String sql =
        "INSERT INTO outbox (tenant_id, shard, version, payload, created_at, topic)"
            + " VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setString(4, payload);
      statement.setTimestamp(5, Timestamp.from(at));
      statement.setString(6, topic);
      statement.executeUpdate();
    }
  }

  public void insertReplan(
      Connection connection,
      String tenant,
      String shard,
      long version,
      int tier,
      int ring,
      long durationMillis,
      long changes,
      String outcome)
      throws SQLException {
    String sql =
        "INSERT INTO replan_run (tenant_id, shard, version, tier, ring, duration_ms, changes, outcome)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setInt(4, tier);
      statement.setInt(5, ring);
      statement.setLong(6, durationMillis);
      statement.setLong(7, changes);
      statement.setString(8, outcome);
      statement.executeUpdate();
    }
  }

  public void insertAudit(Connection connection, String tenant, Instant at) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO audit_log (tenant_id, action, created_at) VALUES (?, ?, ?)")) {
      statement.setString(1, tenant);
      statement.setString(2, "PLAN_PUBLISHED");
      statement.setTimestamp(3, Timestamp.from(at));
      statement.executeUpdate();
    }
  }

  private static OptionalLong lockHead(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql = "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ? FOR UPDATE";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return OptionalLong.empty();
        }
        return OptionalLong.of(rows.getLong(1));
      }
    }
  }

  private static OptionalLong insertHead(
      Connection connection, String tenant, String shard, Instant at) throws SQLException {
    String sql =
        "INSERT INTO plan_head (tenant_id, shard, version, updated_at) VALUES (?, ?, 1, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setTimestamp(3, Timestamp.from(at));
      statement.executeUpdate();
      return OptionalLong.of(1L);
    } catch (SQLException ex) {
      if (UNIQUE_VIOLATION.equals(ex.getSQLState())) {
        return OptionalLong.empty();
      }
      throw ex;
    }
  }

  private static int updateHead(
      Connection connection, String tenant, String shard, long expected, Instant at)
      throws SQLException {
    String sql =
        "UPDATE plan_head SET version = version + 1, updated_at = ?"
            + " WHERE tenant_id = ? AND shard = ? AND version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(at));
      statement.setString(2, tenant);
      statement.setString(3, shard);
      statement.setLong(4, expected);
      return statement.executeUpdate();
    }
  }
}
