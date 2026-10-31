package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.service.bus.Envelope;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Writes live cab energy and plug failures. A lower sequence does not replace a newer one. */
public final class FleetState {

  public void apply(Connection connection, Envelope envelope, Envelope.PlanTrigger trigger)
      throws SQLException {
    DriftEvent.Kind kind = DriftEvent.Kind.valueOf(trigger.kind());
    if (kind == DriftEvent.Kind.BATTERY && trigger.reportedSocWh().isPresent()) {
      long socWh = trigger.reportedSocWh().orElseThrow();
      reading(
          connection,
          envelope.tenant(),
          trigger.subjectId(),
          socWh,
          trigger.observedAt(),
          envelope.sequence());
      return;
    }
    if (kind == DriftEvent.Kind.CHARGER_FAILURE) {
      fail(connection, envelope, trigger);
    }
  }

  public void reading(
      Connection connection, String tenant, String cabId, long socWh, Instant at, long sequence)
      throws SQLException {
    String sql =
        "INSERT INTO vehicle_state (tenant_id, vehicle_id, soc_wh, location_id,"
            + " latitude_micros, longitude_micros, event_time, seq)"
            + " SELECT v.tenant_id, v.vehicle_id, ?, v.location_id, f.latitude_micros,"
            + " f.longitude_micros, ?, ?"
            + " FROM vehicle v JOIN fleet_location f"
            + " ON f.tenant_id = v.tenant_id AND f.location_id = v.location_id"
            + " WHERE v.tenant_id = ? AND v.vehicle_id = ?"
            + " AND f.latitude_micros IS NOT NULL AND f.longitude_micros IS NOT NULL"
            + " ON CONFLICT (tenant_id, vehicle_id) DO UPDATE SET"
            + " soc_wh = EXCLUDED.soc_wh, location_id = EXCLUDED.location_id,"
            + " latitude_micros = EXCLUDED.latitude_micros,"
            + " longitude_micros = EXCLUDED.longitude_micros,"
            + " event_time = EXCLUDED.event_time, seq = EXCLUDED.seq"
            + " WHERE vehicle_state.seq < EXCLUDED.seq";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, socWh);
      statement.setTimestamp(2, Timestamp.from(at));
      statement.setLong(3, sequence);
      statement.setString(4, tenant);
      statement.setString(5, cabId);
      if (statement.executeUpdate() == 0 && !cab(connection, tenant, cabId)) {
        throw new FleetRejected("CAB_MISSING", cabId);
      }
    }
  }

  public void status(Connection connection, String tenant, String chargerId, boolean operational)
      throws SQLException {
    String sql = "UPDATE charger SET status = ? WHERE tenant_id = ? AND charger_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(
          1, operational ? ChargerStatus.OPERATIONAL.name() : ChargerStatus.FAULTY.name());
      statement.setString(2, tenant);
      statement.setString(3, chargerId);
      if (statement.executeUpdate() == 0) {
        throw new FleetRejected("CHARGER_MISSING", chargerId);
      }
    }
  }

  public void fail(Connection connection, Envelope envelope, Envelope.PlanTrigger trigger)
      throws SQLException {
    status(connection, envelope.tenant(), trigger.subjectId(), false);
    String sql =
        "INSERT INTO charger_outage"
            + " (tenant_id, outage_id, charger_id, start_ts, end_ts, source_key)"
            + " SELECT ?, ?, ?, ?, horizon_end, ?"
            + " FROM shard WHERE tenant_id = ? AND shard_id = ? AND horizon_end > ?"
            + " ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, envelope.tenant());
      statement.setString(2, trigger.subjectId() + ":" + envelope.sequence());
      statement.setString(3, trigger.subjectId());
      statement.setTimestamp(4, Timestamp.from(trigger.observedAt()));
      statement.setString(5, envelope.producer() + ":" + envelope.sequence());
      statement.setString(6, envelope.tenant());
      statement.setString(7, trigger.shard());
      statement.setTimestamp(8, Timestamp.from(trigger.observedAt()));
      statement.executeUpdate();
    }
  }

  public String shardOf(Connection connection, String tenant, String cabId) throws SQLException {
    String sql = "SELECT shard_id FROM vehicle WHERE tenant_id = ? AND vehicle_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, cabId);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return "";
        }
        String shard = rows.getString(1);
        return shard == null ? "" : shard;
      }
    }
  }

  public byte[] headImage(Connection connection, String tenant, String shard, long version)
      throws SQLException {
    String sql =
        "SELECT compressed_plan FROM plan_snapshot"
            + " WHERE tenant_id = ? AND shard = ? AND version = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setLong(3, version);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return new byte[0];
        }
        byte[] image = rows.getBytes(1);
        return image == null ? new byte[0] : image;
      }
    }
  }

  private static boolean cab(Connection connection, String tenant, String cabId)
      throws SQLException {
    String sql =
        "SELECT 1 FROM vehicle v JOIN fleet_location f"
            + " ON f.tenant_id = v.tenant_id AND f.location_id = v.location_id"
            + " WHERE v.tenant_id = ? AND v.vehicle_id = ?"
            + " AND f.latitude_micros IS NOT NULL AND f.longitude_micros IS NOT NULL";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, cabId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
