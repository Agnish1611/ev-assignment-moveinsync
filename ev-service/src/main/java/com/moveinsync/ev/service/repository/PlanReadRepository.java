package com.moveinsync.ev.service.repository;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;

/** Read-model rows for one published version. */
public final class PlanReadRepository {

  public void insertStops(
      Connection connection, String tenant, String shard, long version, FleetPlan plan)
      throws SQLException {
    String sql =
        "INSERT INTO route_stop (tenant_id, shard, version, vehicle_id, stop_seq, trip_id,"
            + " start_ts, end_ts, arrival_soc_wh) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (VehicleSchedule schedule : plan.vehicleSchedules()) {
        int seq = 0;
        for (RouteActivity activity : schedule.activities()) {
          if (activity instanceof TripAssignment trip) {
            bindStop(statement, tenant, shard, version, schedule.vehicleId().value(), seq, trip);
            statement.executeUpdate();
            seq++;
          }
        }
      }
    }
  }

  public void insertSessions(
      Connection connection, String tenant, String shard, long version, FleetPlan plan)
      throws SQLException {
    String sql =
        "INSERT INTO charging_session (tenant_id, shard, version, session_id, vehicle_id, hub_id,"
            + " charger_id, start_ts, end_ts, start_soc_wh, target_soc_wh, cost_micros)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (VehicleSchedule schedule : plan.vehicleSchedules()) {
        for (RouteActivity activity : schedule.activities()) {
          if (activity instanceof ChargingSession session) {
            bindSession(statement, tenant, shard, version, session);
            statement.executeUpdate();
          }
        }
      }
    }
  }

  public void insertUnassigned(
      Connection connection, String tenant, String shard, long version, FleetPlan plan)
      throws SQLException {
    String sql =
        "INSERT INTO unassigned_trip (tenant_id, shard, version, trip_id, reason_code, explanation)"
            + " VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (UnassignedTrip unassigned : plan.unassignedTrips()) {
        statement.setString(1, tenant);
        statement.setString(2, shard);
        statement.setLong(3, version);
        statement.setString(4, unassigned.trip().id().value());
        statement.setString(5, unassigned.reasonCode().name());
        statement.setString(6, unassigned.explanation());
        statement.executeUpdate();
      }
    }
  }

  public Map<String, String> assignments(
      Connection connection, String tenant, String shard, long version) throws SQLException {
    String sql =
        "SELECT trip_id, vehicle_id FROM route_stop WHERE tenant_id = ? AND shard = ? AND version = ?";
    Map<String, String> found = new HashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.put(rows.getString(1), rows.getString(2));
        }
      }
    }
    return found;
  }

  private static void bindStop(
      PreparedStatement statement,
      String tenant,
      String shard,
      long version,
      String vehicleId,
      int seq,
      TripAssignment trip)
      throws SQLException {
    statement.setString(1, tenant);
    statement.setString(2, shard);
    statement.setLong(3, version);
    statement.setString(4, vehicleId);
    statement.setInt(5, seq);
    statement.setString(6, trip.trip().id().value());
    statement.setTimestamp(7, Timestamp.from(trip.pickupTime()));
    statement.setTimestamp(8, Timestamp.from(trip.dropoffTime()));
    statement.setLong(9, trip.arrivalAtPickupSocWh());
  }

  private static void bindSession(
      PreparedStatement statement,
      String tenant,
      String shard,
      long version,
      ChargingSession session)
      throws SQLException {
    statement.setString(1, tenant);
    statement.setString(2, shard);
    statement.setLong(3, version);
    statement.setString(4, session.sessionId());
    statement.setString(5, session.vehicleId().value());
    statement.setString(6, session.hubId().value());
    statement.setString(7, session.chargerId().value());
    statement.setTimestamp(8, Timestamp.from(session.startTime()));
    statement.setTimestamp(9, Timestamp.from(session.endTime()));
    statement.setLong(10, session.startSocWh());
    statement.setLong(11, session.targetSocWh());
    statement.setLong(12, session.costMicros());
  }
}
