package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/** Rejects catalog input before it reaches the database. */
final class CatalogRules {

  private static final Pattern CLOCK = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");
  private static final Set<String> ROLES = Set.of("EMPLOYEE", "OPERATOR", "ADMIN");

  private CatalogRules() {
    throw new IllegalStateException("utility");
  }

  static void hub(HubRequest hub, ApiSettings settings) {
    OpaqueIds.require(hub.hubId(), settings.maxIdLength());
    OpaqueIds.require(hub.locationId(), settings.maxIdLength());
    label(hub.name());
    zone(hub.zone());
    if (hub.chargerCount() <= 0 || hub.chargerCount() > settings.maxChargerCount()) {
      throw new ApiException(400, "INVALID_CAPACITY", "The charger count is not valid");
    }
    coordinates(hub.latitudeMicros(), hub.longitudeMicros());
  }

  static void charger(ChargerRequest charger, ApiSettings settings) {
    OpaqueIds.require(charger.chargerId(), settings.maxIdLength());
    OpaqueIds.require(charger.hubId(), settings.maxIdLength());
    if (charger.powerKw() <= 0 || charger.powerKw() > settings.maxPowerKw()) {
      throw new ApiException(400, "INVALID_CAPACITY", "The charger power is not valid");
    }
  }

  static VehicleFacts vehicle(VehicleRequest vehicle, ApiSettings settings) {
    OpaqueIds.require(vehicle.vehicleId(), settings.maxIdLength());
    if (vehicle.batteryWh() <= 0L
        || vehicle.consumptionWhPerKm() <= 0
        || vehicle.maxChargeKw() <= 0) {
      throw new ApiException(400, "INVALID_CAPACITY", "The vehicle capacity is not valid");
    }
    if (vehicle.maxChargeKw() > settings.maxPowerKw()) {
      throw new ApiException(400, "INVALID_CAPACITY", "The vehicle capacity is not valid");
    }
    optionalId(vehicle.shardId(), settings);
    optionalId(vehicle.locationId(), settings);
    charge(vehicle);
    coordinates(vehicle.latitudeMicros(), vehicle.longitudeMicros());
    if (vehicle.locationId() == null
        && (vehicle.latitudeMicros() != null || vehicle.longitudeMicros() != null)) {
      throw new ApiException(400, "MISSING_LOCATION", "The location is not valid");
    }
    Instant available = vehicle.availableFrom() == null ? null : instant(vehicle.availableFrom());
    Window horizon = horizon(vehicle.shardId(), vehicle.horizonStart(), vehicle.horizonEnd());
    if (horizon == null) {
      return new VehicleFacts(available, null, null);
    }
    return new VehicleFacts(available, horizon.start(), horizon.end());
  }

  static TripTimes trip(TripRequest trip, ApiSettings settings) {
    OpaqueIds.require(trip.tripId(), settings.maxIdLength());
    OpaqueIds.require(trip.fromLocation(), settings.maxIdLength());
    OpaqueIds.require(trip.toLocation(), settings.maxIdLength());
    OpaqueIds.require(trip.employeeRef(), settings.maxIdLength());
    if (trip.distanceMetres() < 0) {
      throw new ApiException(400, "INVALID_DISTANCE", "The distance is not valid");
    }
    Instant start = instant(trip.start());
    Instant latest = instant(trip.latestFinish());
    if (latest.isBefore(start)) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
    optionalId(trip.shardId(), settings);
    duration(trip.durationSeconds());
    coordinates(trip.fromLatitudeMicros(), trip.fromLongitudeMicros());
    coordinates(trip.toLatitudeMicros(), trip.toLongitudeMicros());
    Window horizon = horizon(trip.shardId(), trip.horizonStart(), trip.horizonEnd());
    if (horizon == null) {
      return new TripTimes(start, latest, null, null);
    }
    return new TripTimes(start, latest, horizon.start(), horizon.end());
  }

  static void tariff(TariffRequest tariff, ApiSettings settings) {
    OpaqueIds.require(tariff.hubId(), settings.maxIdLength());
    OpaqueIds.require(tariff.windowId(), settings.maxIdLength());
    if (!CLOCK.matcher(tariff.localStart() == null ? "" : tariff.localStart()).matches()
        || !CLOCK.matcher(tariff.localEnd() == null ? "" : tariff.localEnd()).matches()) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
    if (tariff.rateMicros() < 0L) {
      throw new ApiException(400, "INVALID_RATE", "The tariff rate is not valid");
    }
  }

  static void user(UserRequest user, ApiSettings settings) {
    OpaqueIds.require(user.userId(), settings.maxIdLength());
    if (user.role() == null || !ROLES.contains(user.role())) {
      throw new ApiException(400, "INVALID_ROLE", "The role is not allowed");
    }
  }

  static Instant instant(String value) {
    if (value == null) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException ex) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
  }

  private static void label(String value) {
    if (value == null || value.isBlank() || value.length() > 80 || value.indexOf('@') >= 0) {
      throw new ApiException(400, "INVALID_LABEL", "A label is not valid");
    }
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) < ' ') {
        throw new ApiException(400, "INVALID_LABEL", "A label is not valid");
      }
    }
  }

  private static void zone(String value) {
    if (value == null || value.isBlank() || value.length() > 64 || value.indexOf('@') >= 0) {
      throw new ApiException(400, "INVALID_ZONE", "The zone is not valid");
    }
    try {
      ZoneId.of(value);
    } catch (DateTimeException ex) {
      throw new ApiException(400, "INVALID_ZONE", "The zone is not valid");
    }
  }

  private static void optionalId(String value, ApiSettings settings) {
    if (value != null) {
      OpaqueIds.require(value, settings.maxIdLength());
    }
  }

  private static void charge(VehicleRequest vehicle) {
    if (vehicle.shiftStartSocWh() == null) {
      return;
    }
    if (vehicle.shiftStartSocWh() < 0L || vehicle.shiftStartSocWh() > vehicle.batteryWh()) {
      throw new ApiException(400, "INVALID_CAPACITY", "The vehicle capacity is not valid");
    }
  }

  private static void duration(Integer seconds) {
    if (seconds != null && seconds < 0) {
      throw new ApiException(400, "INVALID_DURATION", "The duration is not valid");
    }
  }

  static void coordinates(Integer latitude, Integer longitude) {
    if (latitude == null && longitude == null) {
      return;
    }
    if (latitude == null
        || longitude == null
        || latitude < -90_000_000
        || latitude > 90_000_000
        || longitude < -180_000_000
        || longitude > 180_000_000) {
      throw new ApiException(400, "INVALID_COORDINATES", "The coordinates are not valid");
    }
  }

  private static Window horizon(String shard, String start, String end) {
    if (start == null && end == null) {
      return null;
    }
    if (shard == null || start == null || end == null) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
    Instant opened = instant(start);
    Instant closed = instant(end);
    if (!opened.isBefore(closed)) {
      throw new ApiException(400, "INVALID_TIME", "A timestamp is not valid");
    }
    return new Window(opened, closed);
  }

  record Window(Instant start, Instant end) {}

  record VehicleFacts(Instant availableFrom, Instant horizonStart, Instant horizonEnd) {}

  record TripTimes(Instant start, Instant latest, Instant horizonStart, Instant horizonEnd) {}
}
