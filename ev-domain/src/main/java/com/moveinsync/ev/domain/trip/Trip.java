package com.moveinsync.ev.domain.trip;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain representation of a scheduled passenger trip.
 *
 * <p>In accordance with privacy invariants, {@code employeeRef} is strictly an opaque identifier
 * and must never contain personal data (phone numbers, emails, personal names).
 *
 * @param tenantId tenant identifier
 * @param id unique trip identifier
 * @param startTs planned pickup time in UTC epoch seconds
 * @param fromLocation pickup location node
 * @param toLocation dropoff location node
 * @param distanceMeters planned trip driving distance in metres
 * @param estimatedDurationSeconds planned trip duration in seconds
 * @param latestFinishTs dropoff deadline in UTC epoch seconds
 * @param employeeRef opaque passenger / booking reference ID (strictly non-PII)
 */
public record Trip(
    TenantId tenantId,
    TripId id,
    Instant startTs,
    Location fromLocation,
    Location toLocation,
    int distanceMeters,
    int estimatedDurationSeconds,
    Instant latestFinishTs,
    String employeeRef) {

  public Trip {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(id, "Trip id must not be null");
    Objects.requireNonNull(startTs, "startTs must not be null");
    Objects.requireNonNull(fromLocation, "fromLocation must not be null");
    Objects.requireNonNull(toLocation, "toLocation must not be null");
    Objects.requireNonNull(latestFinishTs, "latestFinishTs must not be null");
    Objects.requireNonNull(employeeRef, "employeeRef must not be null");

    if (employeeRef.isBlank()) {
      throw new IllegalArgumentException("employeeRef must not be blank");
    }
    // Strict privacy rule: reject obvious PII patterns in employeeRef
    if (employeeRef.contains("@") || employeeRef.startsWith("+")) {
      throw new IllegalArgumentException(
          "employeeRef must be an opaque identifier, never an email or phone number");
    }

    if (distanceMeters <= 0) {
      throw new IllegalArgumentException("distanceMeters must be positive: " + distanceMeters);
    }
    if (estimatedDurationSeconds <= 0) {
      throw new IllegalArgumentException(
          "estimatedDurationSeconds must be positive: " + estimatedDurationSeconds);
    }

    Instant plannedDropoff = startTs.plusSeconds(estimatedDurationSeconds);
    if (plannedDropoff.isAfter(latestFinishTs)) {
      throw new IllegalArgumentException(
          "Planned dropoff ("
              + plannedDropoff
              + ") exceeds latestFinishTs ("
              + latestFinishTs
              + ")");
    }
  }

  public Trip(
      TripId id,
      Instant startTs,
      Location fromLocation,
      Location toLocation,
      int distanceMeters,
      int estimatedDurationSeconds,
      Instant latestFinishTs,
      String employeeRef) {
    this(
        TenantId.of("default"),
        id,
        startTs,
        fromLocation,
        toLocation,
        distanceMeters,
        estimatedDurationSeconds,
        latestFinishTs,
        employeeRef);
  }

  /**
   * Calculates the planned dropoff instant (pickup time plus estimated duration).
   *
   * @return planned dropoff time in UTC
   */
  public Instant plannedDropoffTs() {
    return startTs.plusSeconds(estimatedDurationSeconds);
  }

  /**
   * Calculates the schedule slack in seconds (time between planned dropoff and latest allowed
   * finish).
   *
   * @return slack in integer seconds
   */
  public long slackSeconds() {
    return Duration.between(plannedDropoffTs(), latestFinishTs).toSeconds();
  }
}
