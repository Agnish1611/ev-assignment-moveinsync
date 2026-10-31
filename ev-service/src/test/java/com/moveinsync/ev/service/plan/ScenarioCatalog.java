package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/** Writes a planner snapshot as catalog rows. Coordinates stay in the database. */
final class ScenarioCatalog {

  private ScenarioCatalog() {
    throw new IllegalStateException("utility");
  }

  static void store(DataSource source, PlannerInput input, String shard) throws SQLException {
    try (Connection connection = source.getConnection()) {
      connection.setAutoCommit(false);
      String tenant = input.tenantId().value();
      insertTenant(connection, tenant);
      insertCatalog(connection, tenant);
      insertShard(connection, input, shard);
      insertPlaces(connection, tenant, places(input));
      insertHubs(connection, input);
      insertChargers(connection, input);
      insertCabs(connection, input, shard);
      insertTrips(connection, input, shard);
      connection.commit();
    }
  }

  private static Map<String, Location> places(PlannerInput input) {
    Map<String, Location> found = new LinkedHashMap<>();
    for (Hub hub : input.hubs()) {
      found.put(hub.location().id().value(), hub.location());
    }
    for (CabShift cab : input.cabs()) {
      found.put(cab.startLocation().id().value(), cab.startLocation());
    }
    for (Trip trip : input.trips()) {
      found.put(trip.fromLocation().id().value(), trip.fromLocation());
      found.put(trip.toLocation().id().value(), trip.toLocation());
    }
    return found;
  }

  private static void insertTenant(Connection connection, String tenant) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO tenant (tenant_id) VALUES (?) ON CONFLICT DO NOTHING")) {
      statement.setString(1, tenant);
      statement.executeUpdate();
    }
  }

  private static void insertCatalog(Connection connection, String tenant) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO catalog_version (tenant_id, version) VALUES (?, 1)"
                + " ON CONFLICT (tenant_id) DO NOTHING")) {
      statement.setString(1, tenant);
      statement.executeUpdate();
    }
  }

  private static void insertShard(Connection connection, PlannerInput input, String shard)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO shard (tenant_id, shard_id, horizon_start, horizon_end)"
                + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
      statement.setString(1, input.tenantId().value());
      statement.setString(2, shard);
      statement.setTimestamp(3, Timestamp.from(input.horizonStart()));
      statement.setTimestamp(4, Timestamp.from(input.horizonEnd()));
      statement.executeUpdate();
    }
  }

  private static void insertPlaces(
      Connection connection, String tenant, Map<String, Location> places) throws SQLException {
    String sql =
        "INSERT INTO fleet_location"
            + " (tenant_id, location_id, name, latitude_micros, longitude_micros)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Location location : places.values()) {
        statement.setString(1, tenant);
        statement.setString(2, location.id().value());
        statement.setString(3, location.name());
        statement.setInt(4, location.latitudeMicros());
        statement.setInt(5, location.longitudeMicros());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void insertHubs(Connection connection, PlannerInput input) throws SQLException {
    String sql =
        "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Hub hub : input.hubs()) {
        statement.setString(1, input.tenantId().value());
        statement.setString(2, hub.id().value());
        statement.setString(3, hub.name());
        statement.setString(4, hub.location().id().value());
        statement.setString(5, hub.timeZone().getId());
        statement.setInt(6, hub.chargerCount());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void insertChargers(Connection connection, PlannerInput input)
      throws SQLException {
    String sql =
        "INSERT INTO charger (tenant_id, charger_id, hub_id, power_kw, status)"
            + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Charger charger : input.chargers()) {
        statement.setString(1, input.tenantId().value());
        statement.setString(2, charger.id().value());
        statement.setString(3, charger.hubId().value());
        statement.setInt(4, charger.powerKw());
        statement.setString(5, charger.status().name());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void insertCabs(Connection connection, PlannerInput input, String shard)
      throws SQLException {
    String sql =
        "INSERT INTO vehicle (tenant_id, vehicle_id, battery_wh, consumption_wh_per_km,"
            + " max_charge_kw, shard_id, shift_start_soc_wh, location_id, available_from)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (CabShift cab : input.cabs()) {
        bindCab(statement, input.tenantId().value(), shard, cab);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void bindCab(
      PreparedStatement statement, String tenant, String shard, CabShift cab) throws SQLException {
    statement.setString(1, tenant);
    statement.setString(2, cab.vehicle().id().value());
    statement.setLong(3, cab.vehicle().batteryCapacityWh());
    statement.setLong(4, cab.vehicle().baseConsumptionWhPerKm());
    statement.setInt(5, cab.vehicle().maxChargeKw());
    statement.setString(6, shard);
    statement.setLong(7, cab.initialSocWh());
    statement.setString(8, cab.startLocation().id().value());
    statement.setTimestamp(9, Timestamp.from(cab.availableFrom()));
  }

  private static void insertTrips(Connection connection, PlannerInput input, String shard)
      throws SQLException {
    String sql =
        "INSERT INTO trip (tenant_id, trip_id, start_ts, from_loc, to_loc, distance_m,"
            + " latest_finish_ts, employee_ref, shard_id, duration_seconds)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (Trip trip : input.trips()) {
        bindTrip(statement, input.tenantId().value(), shard, trip);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static void bindTrip(PreparedStatement statement, String tenant, String shard, Trip trip)
      throws SQLException {
    statement.setString(1, tenant);
    statement.setString(2, trip.id().value());
    statement.setTimestamp(3, Timestamp.from(trip.startTs()));
    statement.setString(4, trip.fromLocation().id().value());
    statement.setString(5, trip.toLocation().id().value());
    statement.setInt(6, trip.distanceMeters());
    statement.setTimestamp(7, Timestamp.from(trip.latestFinishTs()));
    statement.setString(8, trip.employeeRef());
    statement.setString(9, shard);
    statement.setInt(10, trip.estimatedDurationSeconds());
  }
}
