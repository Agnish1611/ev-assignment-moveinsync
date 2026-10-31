package com.moveinsync.ev.service.repository;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/** Inserts fleet rows for the token tenant. A body cannot name another tenant. */
public final class CatalogRepository {

  public void storeHub(Connection connection, String tenant, HubRequest hub) throws SQLException {
    String sql =
        "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
            + " VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, hub.hubId());
      statement.setString(3, hub.name());
      statement.setString(4, hub.locationId());
      statement.setString(5, hub.zone());
      statement.setInt(6, hub.chargerCount());
      statement.executeUpdate();
    }
  }

  public void storeCharger(Connection connection, String tenant, ChargerRequest charger)
      throws SQLException {
    String sql =
        "INSERT INTO charger (tenant_id, charger_id, hub_id, power_kw, status)"
            + " VALUES (?, ?, ?, ?, 'OPERATIONAL')";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, charger.chargerId());
      statement.setString(3, charger.hubId());
      statement.setInt(4, charger.powerKw());
      statement.executeUpdate();
    }
  }

  public void storeVehicle(
      Connection connection, String tenant, VehicleRequest vehicle, Instant availableFrom)
      throws SQLException {
    String sql =
        "INSERT INTO vehicle (tenant_id, vehicle_id, battery_wh, consumption_wh_per_km,"
            + " max_charge_kw, shard_id, shift_start_soc_wh, location_id, available_from)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, vehicle.vehicleId());
      statement.setLong(3, vehicle.batteryWh());
      statement.setInt(4, vehicle.consumptionWhPerKm());
      statement.setInt(5, vehicle.maxChargeKw());
      statement.setString(6, vehicle.shardId());
      setLong(statement, 7, vehicle.shiftStartSocWh());
      statement.setString(8, vehicle.locationId());
      setInstant(statement, 9, availableFrom);
      statement.executeUpdate();
    }
  }

  public void storeTrip(
      Connection connection, String tenant, TripRequest trip, Instant start, Instant latest)
      throws SQLException {
    String sql =
        "INSERT INTO trip (tenant_id, trip_id, start_ts, from_loc, to_loc, distance_m,"
            + " latest_finish_ts, employee_ref, shard_id, duration_seconds)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, trip.tripId());
      statement.setTimestamp(3, Timestamp.from(start));
      statement.setString(4, trip.fromLocation());
      statement.setString(5, trip.toLocation());
      statement.setInt(6, trip.distanceMetres());
      statement.setTimestamp(7, Timestamp.from(latest));
      statement.setString(8, trip.employeeRef());
      statement.setString(9, trip.shardId());
      setInteger(statement, 10, trip.durationSeconds());
      statement.executeUpdate();
    }
  }

  public void storePlace(
      Connection connection, String tenant, String locationId, int latitude, int longitude)
      throws SQLException {
    String sql =
        "INSERT INTO fleet_location"
            + " (tenant_id, location_id, name, latitude_micros, longitude_micros)"
            + " VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT (tenant_id, location_id) DO UPDATE"
            + " SET latitude_micros = EXCLUDED.latitude_micros,"
            + " longitude_micros = EXCLUDED.longitude_micros";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, locationId);
      statement.setString(3, locationId);
      statement.setInt(4, latitude);
      statement.setInt(5, longitude);
      statement.executeUpdate();
    }
  }

  public void storeShard(
      Connection connection, String tenant, String shard, Instant start, Instant end)
      throws SQLException {
    String sql =
        "INSERT INTO shard (tenant_id, shard_id, horizon_start, horizon_end)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, shard_id) DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, shard);
      statement.setTimestamp(3, Timestamp.from(start));
      statement.setTimestamp(4, Timestamp.from(end));
      statement.executeUpdate();
    }
  }

  public void bump(Connection connection, String tenant) throws SQLException {
    String sql =
        "INSERT INTO catalog_version (tenant_id, version) VALUES (?, 1)"
            + " ON CONFLICT (tenant_id) DO UPDATE"
            + " SET version = catalog_version.version + 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.executeUpdate();
    }
  }

  private static void setLong(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.BIGINT);
    } else {
      statement.setLong(index, value);
    }
  }

  private static void setInteger(PreparedStatement statement, int index, Integer value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.INTEGER);
    } else {
      statement.setInt(index, value);
    }
  }

  private static void setInstant(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.TIMESTAMP);
    } else {
      statement.setTimestamp(index, Timestamp.from(value));
    }
  }

  public void storeTariff(Connection connection, String tenant, TariffRequest tariff)
      throws SQLException {
    String sql =
        "INSERT INTO tariff_window"
            + " (tenant_id, hub_id, window_id, local_start, local_end, rate_micros)"
            + " VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, tariff.hubId());
      statement.setString(3, tariff.windowId());
      statement.setString(4, tariff.localStart());
      statement.setString(5, tariff.localEnd());
      statement.setLong(6, tariff.rateMicros());
      statement.executeUpdate();
    }
  }

  public void storeUser(Connection connection, String tenant, UserRequest user)
      throws SQLException {
    String sql = "INSERT INTO app_user (tenant_id, user_id, role) VALUES (?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, user.userId());
      statement.setString(3, user.role());
      statement.executeUpdate();
    }
  }
}
