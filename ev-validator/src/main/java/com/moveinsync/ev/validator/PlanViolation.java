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
 * @param message detailed diagnostic explanation (strictly non-PII)
 */
public record PlanViolation(
    RuleId ruleId,
    Optional<VehicleId> vehicleId,
    Optional<TripId> tripId,
    Optional<HubId> hubId,
    Optional<Instant> violationTime,
    String message) {

  public PlanViolation {
    Objects.requireNonNull(ruleId, "ruleId must not be null");
    Objects.requireNonNull(vehicleId, "vehicleId must not be null");
    Objects.requireNonNull(tripId, "tripId must not be null");
    Objects.requireNonNull(hubId, "hubId must not be null");
    Objects.requireNonNull(violationTime, "violationTime must not be null");
    Objects.requireNonNull(message, "message must not be null");
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
