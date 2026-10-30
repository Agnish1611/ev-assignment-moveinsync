package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Independent plan validation engine for electric vehicle fleet charging and trip assignments.
 *
 * <p>Enforces the non-negotiable hard invariants H1 through H6, multi-tenant isolation, referential
 * integrity against ground truth, and totality.
 *
 * <p>Architectural Rule: As mandated by AGENTS.md, this validator depends strictly and solely on
 * {@code ev-domain} and standard Java libraries. It has zero coupling with {@code ev-planner-core}
 * or {@code ev-service}.
 */
public final class PlanValidator {

  private static final Comparator<PlanViolation> VIOLATION_ORDER =
      Comparator.comparing(PlanViolation::ruleId)
          .thenComparing(v -> v.vehicleId().map(VehicleId::value).orElse(""))
          .thenComparing(v -> v.tripId().map(TripId::value).orElse(""))
          .thenComparing(v -> v.hubId().map(HubId::value).orElse(""))
          .thenComparing(v -> v.violationTime().map(Instant::toString).orElse(""))
          .thenComparing(PlanViolation::message);

  private final ValidationContext context;

  /**
   * Constructs a plan validator operating against dynamic {@link ValidationInput} ground truths.
   */
  public PlanValidator() {
    this.context = null;
  }

  /**
   * Constructs a plan validator with a legacy static {@link ValidationContext}.
   *
   * @param context legacy validation context
   */
  public PlanValidator(ValidationContext context) {
    this.context = Objects.requireNonNull(context, "ValidationContext must not be null");
  }

  /** Returns the active validation context, if configured. */
  public ValidationContext context() {
    return context;
  }

  /**
   * Performs an exhaustive independent validation check of the candidate fleet plan against
   * authoritative ground truth {@link ValidationInput}.
   *
   * <p>Totality guarantee (D5): Never throws runtime exceptions; traps unexpected failures into
   * {@link RuleId#VALIDATOR_INTERNAL_ERROR} (fail-closed).
   *
   * @param input authoritative ground truth
   * @param candidate the candidate plan to validate
   * @return structured validation report detailing all findings
   */
  public ValidationReport validate(ValidationInput input, FleetPlan candidate) {
    try {
      Objects.requireNonNull(input, "ValidationInput must not be null");
      Objects.requireNonNull(candidate, "FleetPlan candidate must not be null");

      List<PlanViolation> violations = new ArrayList<>();
      checkTenants(input, candidate, violations);
      checkReferentialIntegrity(input, candidate, violations);
      checkTripCompleteness(input, candidate, violations);

      ValidationContext valCtx = input.toValidationContext();
      runInvariants(valCtx, candidate, violations);

      List<PlanViolation> sortedViolations =
          violations.stream().distinct().sorted(VIOLATION_ORDER).toList();

      return new ValidationReport(
          sortedViolations,
          candidate.vehicleSchedules().size(),
          candidate.totalAssignedTrips(),
          candidate.totalChargingSessions());
    } catch (RuntimeException ex) {
      return failClosedReport(candidate, ex);
    }
  }

  /**
   * Legacy validation entry point delegating to ground truth validation.
   *
   * @param plan the candidate plan to validate
   * @return structured validation report detailing all findings
   */
  public ValidationReport validate(FleetPlan plan) {
    if (context == null) {
      throw new IllegalStateException(
          "ValidationContext not provided. Call validate(ValidationInput, FleetPlan) instead.");
    }
    if (plan == null) {
      return failClosedReport(null, new NullPointerException("FleetPlan must not be null"));
    }

    ValidationInput.Builder b =
        ValidationInput.builder()
            .tenantId(TenantId.of(plan.tenantId()))
            .horizon(plan.horizonStart(), plan.horizonEnd())
            .consumptionModel(context.consumptionModel())
            .distanceProvider(context.distanceProvider())
            .chargingCurve(context.chargingCurve())
            .batteryFloorPercentage(context.batteryFloorPercentage())
            .enforceReserveToHub(context.enforceReserveToHub());
    context.hubs().values().forEach(b::addHub);
    context.chargers().values().forEach(b::addCharger);
    for (VehicleSchedule vs : plan.vehicleSchedules()) {
      b.addVehicle(vs.vehicle(), vs.initialLocation(), vs.initialSocWh());
      for (RouteActivity activity : vs.activities()) {
        if (activity instanceof TripAssignment ta) {
          b.addTrip(ta.trip());
        }
      }
    }
    for (UnassignedTrip ut : plan.unassignedTrips()) {
      b.addTrip(ut.trip());
    }
    return validate(b.build(), plan);
  }

  private void checkTenants(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    if (!input.tenantId().value().equals(candidate.tenantId())) {
      violations.add(
          PlanViolation.ofGeneral(
              RuleId.TENANT_MISMATCH,
              "Candidate plan tenant ("
                  + candidate.tenantId()
                  + ") does not match input tenant ("
                  + input.tenantId().value()
                  + ")"));
    }
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      if (!schedule.vehicle().tenantId().value().equals("default")
          && !input.tenantId().equals(schedule.vehicle().tenantId())) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.TENANT_MISMATCH,
                schedule.vehicle().id(),
                schedule.availableFrom(),
                "Vehicle "
                    + schedule.vehicle().id()
                    + " tenant ("
                    + schedule.vehicle().tenantId().value()
                    + ") does not match input tenant ("
                    + input.tenantId().value()
                    + ")"));
      }
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          if (!assign.trip().tenantId().value().equals("default")
              && !input.tenantId().equals(assign.trip().tenantId())) {
            violations.add(
                PlanViolation.ofTrip(
                    RuleId.TENANT_MISMATCH,
                    assign.trip().id(),
                    schedule.vehicle().id(),
                    assign.pickupTime(),
                    "Trip "
                        + assign.trip().id()
                        + " tenant ("
                        + assign.trip().tenantId().value()
                        + ") does not match input tenant ("
                        + input.tenantId().value()
                        + ")"));
          }
        }
      }
    }
    for (UnassignedTrip unassigned : candidate.unassignedTrips()) {
      if (!unassigned.trip().tenantId().value().equals("default")
          && !input.tenantId().equals(unassigned.trip().tenantId())) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TENANT_MISMATCH,
                unassigned.trip().id(),
                null,
                unassigned.trip().startTs(),
                "Unassigned trip "
                    + unassigned.trip().id()
                    + " tenant ("
                    + unassigned.trip().tenantId().value()
                    + ") does not match input tenant ("
                    + input.tenantId().value()
                    + ")"));
      }
    }
  }

  private void checkReferentialIntegrity(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      if (input.findVehicle(schedule.vehicle().id()).isEmpty()) {
        violations.add(
            PlanViolation.ofVehicle(
                RuleId.UNKNOWN_VEHICLE,
                schedule.vehicle().id(),
                schedule.availableFrom(),
                "Vehicle " + schedule.vehicle().id() + " is not registered in ground truth"));
      }
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          if (input.findTrip(assign.trip().id()).isEmpty()) {
            violations.add(
                PlanViolation.ofTrip(
                    RuleId.UNKNOWN_TRIP,
                    assign.trip().id(),
                    schedule.vehicle().id(),
                    assign.pickupTime(),
                    "Trip " + assign.trip().id() + " is not registered in ground truth"));
          }
        } else if (activity instanceof ChargingSession session) {
          if (input.findHub(session.hubId()).isEmpty()) {
            violations.add(
                PlanViolation.ofHub(
                    RuleId.UNKNOWN_HUB,
                    session.hubId(),
                    session.startTime(),
                    "Hub " + session.hubId() + " is not registered in ground truth"));
          }
          if (input.findCharger(session.chargerId()).isEmpty()) {
            violations.add(
                PlanViolation.ofHub(
                    RuleId.UNKNOWN_CHARGER,
                    session.hubId(),
                    session.startTime(),
                    "Charger " + session.chargerId() + " is not registered in ground truth"));
          }
        }
      }
    }
  }

  private void checkTripCompleteness(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    Set<TripId> assignedTripIds = new HashSet<>();
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assign) {
          assignedTripIds.add(assign.trip().id());
        }
      }
    }

    Set<TripId> unassignedTripIds = new HashSet<>();
    for (UnassignedTrip unassigned : candidate.unassignedTrips()) {
      TripId tripId = unassigned.trip().id();
      if (input.findTrip(tripId).isEmpty()) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_UNKNOWN,
                tripId,
                null,
                unassigned.trip().startTs(),
                "Unassigned trip " + tripId + " is not registered in ground truth"));
      }
      if (assignedTripIds.contains(tripId)) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_ASSIGNED_AND_UNASSIGNED,
                tripId,
                null,
                unassigned.trip().startTs(),
                "Trip "
                    + tripId
                    + " appears simultaneously in both assigned schedules and unassigned list"));
      }
      unassignedTripIds.add(tripId);
    }

    for (Trip requiredTrip : input.trips().values()) {
      TripId id = requiredTrip.id();
      if (!assignedTripIds.contains(id) && !unassignedTripIds.contains(id)) {
        violations.add(
            PlanViolation.ofTrip(
                RuleId.TRIP_OMITTED,
                id,
                null,
                requiredTrip.startTs(),
                "Required trip "
                    + id
                    + " is omitted from both assigned schedules and unassigned list"));
      }
    }
  }

  private void runInvariants(
      ValidationContext valCtx, FleetPlan candidate, List<PlanViolation> violations) {
    BatteryFloorValidator batVal = new BatteryFloorValidator(valCtx);
    ChargerCapacityValidator capVal = new ChargerCapacityValidator(valCtx);
    TripTimingValidator timeVal = new TripTimingValidator();
    AssignmentValidator assignVal = new AssignmentValidator(valCtx);
    ReserveToHubValidator resVal = new ReserveToHubValidator(valCtx);
    ChargingCurveValidator curveVal = new ChargingCurveValidator(valCtx);

    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      violations.addAll(batVal.validate(schedule));
    }
    violations.addAll(capVal.validate(candidate));
    violations.addAll(timeVal.validate(candidate));
    violations.addAll(assignVal.validate(candidate));
    violations.addAll(resVal.validate(candidate));
    violations.addAll(curveVal.validate(candidate));
  }

  private ValidationReport failClosedReport(FleetPlan candidate, RuntimeException ex) {
    String msg =
        "Unexpected validator internal error: "
            + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
    int vehicles =
        (candidate != null && candidate.vehicleSchedules() != null)
            ? candidate.vehicleSchedules().size()
            : 0;
    int trips = (candidate != null) ? candidate.totalAssignedTrips() : 0;
    int sessions = (candidate != null) ? candidate.totalChargingSessions() : 0;
    return new ValidationReport(
        List.of(PlanViolation.ofGeneral(RuleId.VALIDATOR_INTERNAL_ERROR, msg)),
        vehicles,
        trips,
        sessions);
  }
}
