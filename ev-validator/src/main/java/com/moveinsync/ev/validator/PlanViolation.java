package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured diagnostic record describing a specific invariant violation detected in a plan.
 *
 * @param ruleId the invariant rule that was violated
 * @param vehicleId vehicle involved in the violation, if applicable
 * @param tripId trip involved in the violation, if applicable
 * @param hubId hub involved in the violation, if applicable
 * @param violationTime time instant at which the violation occurred or was projected, if applicable
 * @param rootCauseKey deterministic deduplication / grouping key for causal analysis
 * @param message detailed diagnostic explanation (strictly non-PII)
 */
public record PlanViolation(
    RuleId ruleId,
    Optional<VehicleId> vehicleId,
    Optional<TripId> tripId,
    Optional<HubId> hubId,
    Optional<Instant> violationTime,
    Optional<String> rootCauseKey,
    String message) {

  public PlanViolation {
    Objects.requireNonNull(ruleId, "ruleId must not be null");
    Objects.requireNonNull(vehicleId, "vehicleId must not be null");
    Objects.requireNonNull(tripId, "tripId must not be null");
    Objects.requireNonNull(hubId, "hubId must not be null");
    Objects.requireNonNull(violationTime, "violationTime must not be null");
    Objects.requireNonNull(rootCauseKey, "rootCauseKey must not be null");
    Objects.requireNonNull(message, "message must not be null");
  }

  public PlanViolation(
      RuleId ruleId,
      Optional<VehicleId> vehicleId,
      Optional<TripId> tripId,
      Optional<HubId> hubId,
      Optional<Instant> violationTime,
      String message) {
    this(
        ruleId,
        vehicleId,
        tripId,
        hubId,
        violationTime,
        Optional.of(deriveDefaultRootCauseKey(ruleId, vehicleId, tripId, hubId)),
        message);
  }

  private static String deriveDefaultRootCauseKey(
      RuleId ruleId,
      Optional<VehicleId> vehicleId,
      Optional<TripId> tripId,
      Optional<HubId> hubId) {
    return ruleId.name()
        + ":"
        + vehicleId
            .map(VehicleId::value)
            .orElseGet(
                () ->
                    tripId
                        .map(TripId::value)
                        .orElseGet(() -> hubId.map(HubId::value).orElse("general")));
  }

  public PlanViolation withRootCauseKey(String key) {
    Objects.requireNonNull(key, "rootCauseKey must not be null");
    return new PlanViolation(
        ruleId, vehicleId, tripId, hubId, violationTime, Optional.of(key), message);
  }

  public static PlanViolation ofVehicle(
      RuleId ruleId, VehicleId vehicleId, Instant time, String message) {
    return new PlanViolation(
        ruleId,
        Optional.of(vehicleId),
        Optional.empty(),
        Optional.empty(),
        Optional.ofNullable(time),
        message);
  }

  public static PlanViolation ofTrip(
      RuleId ruleId, TripId tripId, VehicleId vehicleId, Instant time, String message) {
    return new PlanViolation(
        ruleId,
        Optional.ofNullable(vehicleId),
        Optional.of(tripId),
        Optional.empty(),
        Optional.ofNullable(time),
        message);
  }

  public static PlanViolation ofHub(RuleId ruleId, HubId hubId, Instant time, String message) {
    return new PlanViolation(
        ruleId,
        Optional.empty(),
        Optional.empty(),
        Optional.of(hubId),
        Optional.ofNullable(time),
        message);
  }

  public static PlanViolation ofGeneral(RuleId ruleId, String message) {
    return new PlanViolation(
        ruleId, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), message);
  }
}
