package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.BulkResult;
import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.Created;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.Intake;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.CatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
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
    return store(
        caller,
        key,
        "/v1/admin/hubs",
        "HUB",
        hub.hubId(),
        connection -> {
          CatalogWrites.hub(connection, catalog, settings, caller.tenant(), hub);
          return new Created(hub.hubId());
        });
  }

  public ResponseEntity<BulkResult> hubs(Caller caller, String key, List<Intake<HubRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/hubs",
        "HUB",
        rows,
        HubRequest::hubId,
        (connection, hub) ->
            CatalogWrites.hub(connection, catalog, settings, caller.tenant(), hub));
  }

  public ResponseEntity<Created> charger(Caller caller, String key, ChargerRequest charger) {
    return store(
        caller,
        key,
        "/v1/admin/chargers",
        "CHARGER",
        charger.chargerId(),
        connection -> {
          CatalogWrites.charger(connection, catalog, settings, caller.tenant(), charger);
          return new Created(charger.chargerId());
        });
  }

  public ResponseEntity<BulkResult> chargers(
      Caller caller, String key, List<Intake<ChargerRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/chargers",
        "CHARGER",
        rows,
        ChargerRequest::chargerId,
        (connection, charger) ->
            CatalogWrites.charger(connection, catalog, settings, caller.tenant(), charger));
  }

  public ResponseEntity<Created> vehicle(Caller caller, String key, VehicleRequest vehicle) {
    return store(
        caller,
        key,
        "/v1/admin/vehicles",
        "VEHICLE",
        vehicle.vehicleId(),
        connection -> {
          CatalogWrites.vehicle(connection, catalog, settings, caller.tenant(), vehicle);
          return new Created(vehicle.vehicleId());
        });
  }

  public ResponseEntity<BulkResult> vehicles(
      Caller caller, String key, List<Intake<VehicleRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/vehicles",
        "VEHICLE",
        rows,
        VehicleRequest::vehicleId,
        (connection, vehicle) ->
            CatalogWrites.vehicle(connection, catalog, settings, caller.tenant(), vehicle));
  }

  public ResponseEntity<Created> trip(Caller caller, String key, TripRequest trip) {
    return store(
        caller,
        key,
        "/v1/admin/trips",
        "TRIP",
        trip.tripId(),
        connection -> {
          CatalogWrites.trip(connection, catalog, settings, caller.tenant(), trip);
          return new Created(trip.tripId());
        });
  }

  public ResponseEntity<BulkResult> trips(
      Caller caller, String key, List<Intake<TripRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/trips",
        "TRIP",
        rows,
        TripRequest::tripId,
        (connection, trip) ->
            CatalogWrites.trip(connection, catalog, settings, caller.tenant(), trip));
  }

  public ResponseEntity<Created> tariff(Caller caller, String key, TariffRequest tariff) {
    return store(
        caller,
        key,
        "/v1/admin/tariffs",
        "TARIFF",
        tariff.windowId(),
        connection -> {
          CatalogWrites.tariff(connection, catalog, settings, caller.tenant(), tariff);
          return new Created(tariff.windowId());
        });
  }

  public ResponseEntity<BulkResult> tariffs(
      Caller caller, String key, List<Intake<TariffRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/tariffs",
        "TARIFF",
        rows,
        TariffRequest::windowId,
        (connection, tariff) ->
            CatalogWrites.tariff(connection, catalog, settings, caller.tenant(), tariff));
  }

  public ResponseEntity<Created> user(Caller caller, String key, UserRequest user) {
    return store(
        caller,
        key,
        "/v1/admin/users",
        "USER",
        user.userId(),
        connection -> {
          CatalogWrites.user(connection, catalog, settings, caller.tenant(), user);
          return new Created(user.userId());
        });
  }

  public ResponseEntity<BulkResult> users(
      Caller caller, String key, List<Intake<UserRequest>> rows) {
    return batch(
        caller,
        key,
        "/v1/admin/users",
        "USER",
        rows,
        UserRequest::userId,
        (connection, user) ->
            CatalogWrites.user(connection, catalog, settings, caller.tenant(), user));
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
          catalog.bump(connection, caller.tenant());
          announce(caller, kind, subject);
          return created;
        });
  }

  private <T> ResponseEntity<BulkResult> batch(
      Caller caller,
      String key,
      String path,
      String kind,
      List<Intake<T>> rows,
      Function<T, String> identifier,
      CatalogIngest.RowStore<T> store) {
    if (rows == null || rows.isEmpty() || rows.size() > settings.maxBulkItems()) {
      throw new ApiException(400, "BULK_LIMIT", "The batch size is not valid");
    }
    return calls.once(
        caller,
        key,
        "POST",
        path,
        HttpStatus.OK.value(),
        BulkResult.class,
        connection -> {
          CatalogIngest.Outcome outcome =
              CatalogIngest.apply(connection, rows, settings.maxIdLength(), identifier, store);
          if (outcome.stored() > 0) {
            catalog.bump(connection, caller.tenant());
            announce(caller, kind, "bulk");
          }
          return outcome.result();
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
