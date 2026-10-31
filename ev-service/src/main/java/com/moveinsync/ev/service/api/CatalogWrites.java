package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.api.CatalogRules.TripTimes;
import com.moveinsync.ev.service.api.CatalogRules.VehicleFacts;
import com.moveinsync.ev.service.repository.CatalogRepository;
import java.sql.Connection;
import java.sql.SQLException;

/** Validates one catalog row and inserts it. A batch catches a bad row and continues. */
final class CatalogWrites {

  private CatalogWrites() {
    throw new IllegalStateException("utility");
  }

  static void hub(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      HubRequest hub)
      throws SQLException {
    CatalogRules.hub(hub, settings);
    catalog.storeHub(connection, tenant, hub);
    place(
        connection, catalog, tenant, hub.locationId(), hub.latitudeMicros(), hub.longitudeMicros());
  }

  static void charger(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      ChargerRequest charger)
      throws SQLException {
    CatalogRules.charger(charger, settings);
    catalog.storeCharger(connection, tenant, charger);
  }

  static void vehicle(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      VehicleRequest vehicle)
      throws SQLException {
    VehicleFacts facts = CatalogRules.vehicle(vehicle, settings);
    if (facts.horizonStart() != null) {
      catalog.storeShard(
          connection, tenant, vehicle.shardId(), facts.horizonStart(), facts.horizonEnd());
    }
    place(
        connection,
        catalog,
        tenant,
        vehicle.locationId(),
        vehicle.latitudeMicros(),
        vehicle.longitudeMicros());
    catalog.storeVehicle(connection, tenant, vehicle, facts.availableFrom());
  }

  static void trip(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      TripRequest trip)
      throws SQLException {
    TripTimes times = CatalogRules.trip(trip, settings);
    if (times.horizonStart() != null) {
      catalog.storeShard(
          connection, tenant, trip.shardId(), times.horizonStart(), times.horizonEnd());
    }
    place(
        connection,
        catalog,
        tenant,
        trip.fromLocation(),
        trip.fromLatitudeMicros(),
        trip.fromLongitudeMicros());
    place(
        connection,
        catalog,
        tenant,
        trip.toLocation(),
        trip.toLatitudeMicros(),
        trip.toLongitudeMicros());
    catalog.storeTrip(connection, tenant, trip, times.start(), times.latest());
  }

  static void tariff(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      TariffRequest tariff)
      throws SQLException {
    CatalogRules.tariff(tariff, settings);
    catalog.storeTariff(connection, tenant, tariff);
  }

  static void user(
      Connection connection,
      CatalogRepository catalog,
      ApiSettings settings,
      String tenant,
      UserRequest user)
      throws SQLException {
    CatalogRules.user(user, settings);
    catalog.storeUser(connection, tenant, user);
  }

  private static void place(
      Connection connection,
      CatalogRepository catalog,
      String tenant,
      String locationId,
      Integer latitude,
      Integer longitude)
      throws SQLException {
    if (latitude == null || longitude == null) {
      return;
    }
    catalog.storePlace(connection, tenant, locationId, latitude, longitude);
  }
}
