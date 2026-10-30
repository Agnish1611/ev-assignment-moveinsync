package com.moveinsync.ev.service.repository;

import com.moveinsync.ev.service.api.ApiModels.OwnTrip;
import com.moveinsync.ev.service.api.ApiModels.PlanDiffView;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.SessionView;
import com.moveinsync.ev.service.api.ApiModels.StopView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads a published plan. The tenant predicate is the application check beside row-level security.
 */
public final class PlanViewRepository {

  public OptionalLong currentVersion(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql = "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?";
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

  public Optional<PlanVersionView> version(
      Connection connection, String tenant, String shard, long version) throws SQLException {
    String sql =
        "SELECT parent_version, plan_id, created_at FROM plan_version"
            + " WHERE tenant_id = ? AND shard = ? AND version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        long parent = rows.getLong(1);
        Long parentVersion = rows.wasNull() ? null : parent;
        Timestamp created = rows.getTimestamp(3);
        return Optional.of(
            new PlanVersionView(
                shard,
                version,
                parentVersion,
                rows.getString(2),
                created == null ? "" : created.toInstant().toString()));
      }
    }
  }

  public Optional<String> validationReport(
      Connection connection, String tenant, String shard, long version) throws SQLException {
    return text(
        connection,
        "SELECT validator_report FROM plan_version WHERE tenant_id = ? AND shard = ? AND version = ?",
        tenant,
        shard,
        version);
  }

  public Optional<PlanDiffView> diff(
      Connection connection, String tenant, String shard, long version, long from)
      throws SQLException {
    String sql =
        "SELECT v.parent_version, d.changes FROM plan_version v"
            + " JOIN plan_diff d ON d.tenant_id = v.tenant_id AND d.shard = v.shard"
            + " AND d.version = v.version"
            + " WHERE v.tenant_id = ? AND v.shard = ? AND v.version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        long parent = rows.getLong(1);
        long expected = rows.wasNull() ? 0L : parent;
        if (expected != from) {
          return Optional.empty();
        }
        return Optional.of(new PlanDiffView(shard, version, from, rows.getString(2)));
      }
    }
  }

  public List<UnassignedView> unassigned(
      Connection connection, String tenant, String shard, long version) throws SQLException {
    String sql =
        "SELECT trip_id, reason_code, explanation FROM unassigned_trip"
            + " WHERE tenant_id = ? AND shard = ? AND version = ? ORDER BY trip_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        List<UnassignedView> found = new ArrayList<>();
        while (rows.next()) {
          found.add(new UnassignedView(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
        return List.copyOf(found);
      }
    }
  }

  public List<StopView> stops(
      Connection connection, String tenant, String shard, long version, String cabId)
      throws SQLException {
    String sql =
        "SELECT trip_id, start_ts, end_ts, arrival_soc_wh FROM route_stop"
            + " WHERE tenant_id = ? AND shard = ? AND version = ? AND vehicle_id = ?"
            + " ORDER BY stop_seq";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setString(4, cabId);
      try (ResultSet rows = statement.executeQuery()) {
        List<StopView> found = new ArrayList<>();
        while (rows.next()) {
          found.add(
              new StopView(rows.getString(1), instant(rows, 2), instant(rows, 3), rows.getLong(4)));
        }
        return List.copyOf(found);
      }
    }
  }

  public List<SessionView> sessions(
      Connection connection, String tenant, String shard, long version, String cabId)
      throws SQLException {
    String sql =
        "SELECT session_id, charger_id, start_ts, end_ts, start_soc_wh, target_soc_wh"
            + " FROM charging_session WHERE tenant_id = ? AND shard = ? AND version = ?"
            + " AND vehicle_id = ? ORDER BY start_ts";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      statement.setString(4, cabId);
      try (ResultSet rows = statement.executeQuery()) {
        List<SessionView> found = new ArrayList<>();
        while (rows.next()) {
          found.add(
              new SessionView(
                  rows.getString(1),
                  rows.getString(2),
                  instant(rows, 3),
                  instant(rows, 4),
                  rows.getLong(5),
                  rows.getLong(6)));
        }
        return List.copyOf(found);
      }
    }
  }

  public List<OwnTrip> ownTrips(Connection connection, String tenant, String employee)
      throws SQLException {
    String sql =
        "SELECT t.trip_id,"
            + " (SELECT rs.vehicle_id FROM route_stop rs"
            + " JOIN plan_head h ON h.tenant_id = rs.tenant_id AND h.shard = rs.shard"
            + " AND h.version = rs.version"
            + " WHERE rs.tenant_id = t.tenant_id AND rs.trip_id = t.trip_id LIMIT 1),"
            + " (SELECT rs.end_ts FROM route_stop rs"
            + " JOIN plan_head h ON h.tenant_id = rs.tenant_id AND h.shard = rs.shard"
            + " AND h.version = rs.version"
            + " WHERE rs.tenant_id = t.tenant_id AND rs.trip_id = t.trip_id LIMIT 1)"
            + " FROM trip t WHERE t.tenant_id = ? AND t.employee_ref = ? ORDER BY t.trip_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, employee);
      try (ResultSet rows = statement.executeQuery()) {
        List<OwnTrip> found = new ArrayList<>();
        while (rows.next()) {
          String cab = rows.getString(2);
          String eta = instant(rows, 3);
          String status = cab == null ? "UNASSIGNED" : "ASSIGNED";
          found.add(new OwnTrip(rows.getString(1), status, cab == null ? "" : cab, eta));
        }
        return List.copyOf(found);
      }
    }
  }

  private static Optional<String> text(
      Connection connection, String sql, String tenant, String shard, long version)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(rows.getString(1));
      }
    }
  }

  private static String instant(ResultSet rows, int column) throws SQLException {
    Timestamp value = rows.getTimestamp(column);
    if (value == null) {
      return "";
    }
    return value.toInstant().toString();
  }
}
