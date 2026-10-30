package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.Created;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.api.CatalogRules.TripTimes;
import com.moveinsync.ev.service.repository.CatalogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Admin catalog writes. Every row uses the token tenant. */
public final class CatalogService {

  private static final int CREATED = HttpStatus.CREATED.value();

  private final IdempotentCalls calls;
  private final CatalogRepository catalog;
  private final ApiSettings settings;

  public CatalogService(IdempotentCalls calls, CatalogRepository catalog, ApiSettings settings) {
    this.calls = calls;
    this.catalog = catalog;
    this.settings = settings;
  }

  public ResponseEntity<Created> hub(Caller caller, String key, HubRequest hub) {
    CatalogRules.hub(hub, settings);
    return store(
        caller,
        key,
        "/v1/admin/hubs",
        connection -> {
          catalog.storeHub(connection, caller.tenant(), hub);
          return new Created(hub.hubId());
        });
  }

  public ResponseEntity<Created> charger(Caller caller, String key, ChargerRequest charger) {
    CatalogRules.charger(charger, settings);
    return store(
        caller,
        key,
        "/v1/admin/chargers",
        connection -> {
          catalog.storeCharger(connection, caller.tenant(), charger);
          return new Created(charger.chargerId());
        });
  }

  public ResponseEntity<Created> vehicle(Caller caller, String key, VehicleRequest vehicle) {
    CatalogRules.vehicle(vehicle, settings);
    return store(
        caller,
        key,
        "/v1/admin/vehicles",
        connection -> {
          catalog.storeVehicle(connection, caller.tenant(), vehicle);
          return new Created(vehicle.vehicleId());
        });
  }

  public ResponseEntity<Created> trip(Caller caller, String key, TripRequest trip) {
    TripTimes times = CatalogRules.trip(trip, settings);
    return store(
        caller,
        key,
        "/v1/admin/trips",
        connection -> {
          catalog.storeTrip(connection, caller.tenant(), trip, times.start(), times.latest());
          return new Created(trip.tripId());
        });
  }

  public ResponseEntity<Created> tariff(Caller caller, String key, TariffRequest tariff) {
    CatalogRules.tariff(tariff, settings);
    return store(
        caller,
        key,
        "/v1/admin/tariffs",
        connection -> {
          catalog.storeTariff(connection, caller.tenant(), tariff);
          return new Created(tariff.windowId());
        });
  }

  public ResponseEntity<Created> user(Caller caller, String key, UserRequest user) {
    CatalogRules.user(user, settings);
    return store(
        caller,
        key,
        "/v1/admin/users",
        connection -> {
          catalog.storeUser(connection, caller.tenant(), user);
          return new Created(user.userId());
        });
  }

  private ResponseEntity<Created> store(
      Caller caller, String key, String path, AppConnections.SqlWork<Created> work) {
    return calls.once(caller, key, "POST", path, CREATED, Created.class, work);
  }
}
