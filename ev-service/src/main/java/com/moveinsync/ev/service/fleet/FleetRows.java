package com.moveinsync.ev.service.fleet;

import java.time.Instant;

/** Rows from one repeatable read. Null coordinates are incomplete, not a location. */
public final class FleetRows {

  private FleetRows() {
    throw new IllegalStateException("utility");
  }

  public record ShardRow(String shardId, Instant start, Instant end, long catalogVersion) {}

  public record PlaceRow(String id, String name, Integer latitudeMicros, Integer longitudeMicros) {}

  public record HubRow(
      String id, String name, String locationId, String zoneId, int chargerCount) {}

  public record ChargerRow(String id, String hubId, int powerKw, String status) {}

  public record OutageRow(String chargerId, Instant start, Instant end, String sourceKey) {}

  public record CabRow(
      String id,
      long batteryWh,
      long consumptionWhPerKm,
      int maxChargeKw,
      Long shiftStartSocWh,
      String locationId,
      Instant availableFrom,
      Long stateSocWh,
      String stateLocationId,
      Integer stateLatitudeMicros,
      Integer stateLongitudeMicros) {}

  public record TripRow(
      String id,
      Instant start,
      String fromLocationId,
      String toLocationId,
      int distanceMetres,
      Integer durationSeconds,
      Instant latestFinish,
      String employeeRef) {}

  public record TariffRow(String hubId, String localStart, String localEnd, long rateMicros) {}
}
