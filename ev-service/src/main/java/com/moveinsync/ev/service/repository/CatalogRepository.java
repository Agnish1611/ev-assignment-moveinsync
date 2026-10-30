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

  public void storeVehicle(Connection connection, String tenant, VehicleRequest vehicle)
      throws SQLException {
    String sql =
        "INSERT INTO vehicle"
            + " (tenant_id, vehicle_id, battery_wh, consumption_wh_per_km, max_charge_kw)"
            + " VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, vehicle.vehicleId());
      statement.setLong(3, vehicle.batteryWh());
      statement.setInt(4, vehicle.consumptionWhPerKm());
      statement.setInt(5, vehicle.maxChargeKw());
      statement.executeUpdate();
    }
  }

  public void storeTrip(
      Connection connection, String tenant, TripRequest trip, Instant start, Instant latest)
      throws SQLException {
    String sql =
        "INSERT INTO trip (tenant_id, trip_id, start_ts, from_loc, to_loc, distance_m,"
            + " latest_finish_ts, employee_ref) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, trip.tripId());
      statement.setTimestamp(3, Timestamp.from(start));
      statement.setString(4, trip.fromLocation());
      statement.setString(5, trip.toLocation());
      statement.setInt(6, trip.distanceMetres());
      statement.setTimestamp(7, Timestamp.from(latest));
      statement.setString(8, trip.employeeRef());
      statement.executeUpdate();
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
