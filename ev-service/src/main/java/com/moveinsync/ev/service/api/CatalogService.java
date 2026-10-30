package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.Created;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.api.CatalogRules.TripTimes;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.CatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Admin catalog writes. Every row uses the token tenant. A replay does not publish again. */
public final class CatalogService {

  private static final int CREATED = HttpStatus.CREATED.value();

  private final IdempotentCalls calls;
  private final CatalogRepository catalog;
  private final ApiSettings settings;
  private final MessageBus bus;
  private final Clock clock;

  public CatalogService(
      IdempotentCalls calls,
      CatalogRepository catalog,
      ApiSettings settings,
      MessageBus bus,
      Clock clock) {
    this.calls = calls;
    this.catalog = catalog;
    this.settings = settings;
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public ResponseEntity<Created> hub(Caller caller, String key, HubRequest hub) {
    CatalogRules.hub(hub, settings);
    return store(
        caller,
        key,
        "/v1/admin/hubs",
        "HUB",
        hub.hubId(),
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
        "CHARGER",
        charger.chargerId(),
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
        "VEHICLE",
        vehicle.vehicleId(),
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
        "TRIP",
        trip.tripId(),
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
        "TARIFF",
        tariff.windowId(),
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
        "USER",
        user.userId(),
        connection -> {
          catalog.storeUser(connection, caller.tenant(), user);
          return new Created(user.userId());
        });
  }

  private ResponseEntity<Created> store(
      Caller caller,
      String key,
      String path,
      String kind,
      String subject,
      AppConnections.SqlWork<Created> work) {
    return calls.once(
        caller,
        key,
        "POST",
        path,
        CREATED,
        Created.class,
        connection -> {
          Created created = work.apply(connection);
          announce(caller, kind, subject);
          return created;
        });
  }

  private void announce(Caller caller, String kind, String subject) {
    Instant now = clock.instant();
    long sequence = now.getEpochSecond();
    if (sequence < 0L) {
      throw new IllegalArgumentException("event time must not be before the epoch");
    }
    Envelope envelope =
        new Envelope(
            caller.tenant() + ":" + kind + ":" + subject + ":" + sequence,
            caller.tenant(),
            caller.userId(),
            sequence,
            now,
            Envelope.SCHEMA_VERSION,
            new Envelope.ConfigChanged(kind, subject));
    bus.send(
        BusRecord.of(Topics.CONFIG, caller.tenant() + ":" + kind, EnvelopeCodec.write(envelope)));
  }
}
