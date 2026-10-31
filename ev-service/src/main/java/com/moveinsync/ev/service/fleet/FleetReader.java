package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.service.fleet.FleetRows.CabRow;
import com.moveinsync.ev.service.fleet.FleetRows.ChargerRow;
import com.moveinsync.ev.service.fleet.FleetRows.HubRow;
import com.moveinsync.ev.service.fleet.FleetRows.OutageRow;
import com.moveinsync.ev.service.fleet.FleetRows.PlaceRow;
import com.moveinsync.ev.service.fleet.FleetRows.ShardRow;
import com.moveinsync.ev.service.fleet.FleetRows.TariffRow;
import com.moveinsync.ev.service.fleet.FleetRows.TripRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads one shard. The caller holds the repeatable-read transaction. */
final class FleetReader {

  FleetSnapshot read(Connection connection, String tenant, String shard) throws SQLException {
    Optional<ShardRow> found = shard(connection, tenant, shard);
    if (found.isEmpty()) {
      throw new FleetRejected("SHARD_MISSING", shard);
    }
    return new FleetSnapshot(
        tenant,
        found.orElseThrow(),
        places(connection, tenant),
        hubs(connection, tenant),
        chargers(connection, tenant),
        outages(connection, tenant),
        cabs(connection, tenant, shard),
        trips(connection, tenant, shard),
        tariffs(connection, tenant));
  }

  private Optional<ShardRow> shard(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql =
        "SELECT s.horizon_start, s.horizon_end, c.version"
            + " FROM shard s JOIN catalog_version c ON c.tenant_id = s.tenant_id"
            + " WHERE s.tenant_id = ? AND s.shard_id = ?";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new ShardRow(
                shard,
                rows.getTimestamp(1).toInstant(),
                rows.getTimestamp(2).toInstant(),
                rows.getLong(3)));
      }
    }
  }

  private List<PlaceRow> places(Connection connection, String tenant) throws SQLException {
    String sql =
        "SELECT location_id, name, latitude_micros, longitude_micros FROM fleet_location"
            + " WHERE tenant_id = ? ORDER BY location_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      try (ResultSet rows = statement.executeQuery()) {
        List<PlaceRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(place(rows));
        }
        return found;
      }
    }
  }

  private static PlaceRow place(ResultSet rows) throws SQLException {
    Integer latitude = rows.getObject(3, Integer.class);
    Integer longitude = rows.getObject(4, Integer.class);
    return new PlaceRow(rows.getString(1), rows.getString(2), latitude, longitude);
  }

  private List<HubRow> hubs(Connection connection, String tenant) throws SQLException {
    String sql =
        "SELECT hub_id, name, location_id, zone_id, charger_count FROM hub"
            + " WHERE tenant_id = ? ORDER BY hub_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      try (ResultSet rows = statement.executeQuery()) {
        List<HubRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(
              new HubRow(
                  rows.getString(1),
                  rows.getString(2),
                  rows.getString(3),
                  rows.getString(4),
                  rows.getInt(5)));
        }
        return found;
      }
    }
  }

  private List<ChargerRow> chargers(Connection connection, String tenant) throws SQLException {
    String sql =
        "SELECT charger_id, hub_id, power_kw, status FROM charger"
            + " WHERE tenant_id = ? ORDER BY charger_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      try (ResultSet rows = statement.executeQuery()) {
        List<ChargerRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(
              new ChargerRow(
                  rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4)));
        }
        return found;
      }
    }
  }

  private List<OutageRow> outages(Connection connection, String tenant) throws SQLException {
    String sql =
        "SELECT charger_id, start_ts, end_ts, source_key FROM charger_outage"
            + " WHERE tenant_id = ? ORDER BY charger_id, start_ts, outage_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      try (ResultSet rows = statement.executeQuery()) {
        List<OutageRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(
              new OutageRow(
                  rows.getString(1),
                  rows.getTimestamp(2).toInstant(),
                  rows.getTimestamp(3).toInstant(),
                  rows.getString(4)));
        }
        return found;
      }
    }
  }

  private List<CabRow> cabs(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql =
        "SELECT v.vehicle_id, v.battery_wh, v.consumption_wh_per_km, v.max_charge_kw,"
            + " v.shift_start_soc_wh, v.location_id, v.available_from,"
            + " s.soc_wh, s.location_id, s.latitude_micros, s.longitude_micros"
            + " FROM vehicle v LEFT JOIN vehicle_state s"
            + " ON s.tenant_id = v.tenant_id AND s.vehicle_id = v.vehicle_id"
            + " WHERE v.tenant_id = ? AND v.shard_id = ? ORDER BY v.vehicle_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      try (ResultSet rows = statement.executeQuery()) {
        List<CabRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(cab(rows));
        }
        return found;
      }
    }
  }

  private static CabRow cab(ResultSet rows) throws SQLException {
    return new CabRow(
        rows.getString(1),
        rows.getLong(2),
        rows.getLong(3),
        rows.getInt(4),
        rows.getObject(5, Long.class),
        rows.getString(6),
        instant(rows, 7),
        rows.getObject(8, Long.class),
        rows.getString(9),
        rows.getObject(10, Integer.class),
        rows.getObject(11, Integer.class));
  }

  private List<TripRow> trips(Connection connection, String tenant, String shard)
      throws SQLException {
    String sql =
        "SELECT trip_id, start_ts, from_loc, to_loc, distance_m, duration_seconds,"
            + " latest_finish_ts, employee_ref FROM trip"
            + " WHERE tenant_id = ? AND shard_id = ? ORDER BY start_ts, trip_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      try (ResultSet rows = statement.executeQuery()) {
        List<TripRow> found = new ArrayList<>();
        while (rows.next()) {
          found.add(trip(rows));
        }
        return found;
      }
    }
  }

  private static TripRow trip(ResultSet rows) throws SQLException {
    return new TripRow(
        rows.getString(1),
        rows.getTimestamp(2).toInstant(),
        rows.getString(3),
        rows.getString(4),
        rows.getInt(5),
        rows.getObject(6, Integer.class),
        rows.getTimestamp(7).toInstant(),
        rows.getString(8));
  }

  private List<TariffRow> tariffs(Connection connection, String tenant) throws SQLException {
    String sql =
        "SELECT hub_id, local_start, local_end, rate_micros FROM tariff_window"
            + " WHERE tenant_id = ? ORDER BY hub_id, window_id";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      try (ResultSet rows = statement.executeQuery()) {
        return readTariffs(rows);
      }
    }
  }

  private static List<TariffRow> readTariffs(ResultSet rows) throws SQLException {
    List<TariffRow> found = new ArrayList<>();
    while (rows.next()) {
      found.add(
          new TariffRow(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)));
    }
    return found;
  }

  private static Instant instant(ResultSet rows, int column) throws SQLException {
    Timestamp stamp = rows.getTimestamp(column);
    if (stamp == null) {
      return null;
    }
    return stamp.toInstant();
  }
}
