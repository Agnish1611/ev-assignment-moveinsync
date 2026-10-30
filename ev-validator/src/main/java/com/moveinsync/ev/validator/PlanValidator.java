package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Independent plan validator verifying physical invariants H1 through H6 and operational rules.
 *
 * <p>Enforces the independent trust model (Decision D1). Only {@link #validate(ValidationInput,
 * FleetPlan)} is supported as a public entry point. Candidate plans cannot supply ground truth.
 */
public final class PlanValidator {

  private static final Comparator<PlanViolation> VIOLATION_ORDER =
      Comparator.comparing(PlanViolation::ruleId)
          .thenComparing(v -> v.vehicleId().map(VehicleId::value).orElse(""))
          .thenComparing(v -> v.tripId().map(TripId::value).orElse(""))
          .thenComparing(v -> v.hubId().map(HubId::value).orElse(""))
          .thenComparing(v -> v.violationTime().map(Instant::toString).orElse(""))
          .thenComparing(PlanViolation::message);

  public PlanValidator() {}

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
      PlanTenancyValidator.checkTenants(input, candidate, violations);
      PlanReferentialIntegrityValidator.checkReferentialIntegrity(input, candidate, violations);
      PlanTripCompletenessValidator.checkTripCompleteness(input, candidate, violations);

      ValidationContext valCtx = input.toValidationContext();
      runInvariants(valCtx, candidate, violations);

      TariffCostValidator tariffVal = new TariffCostValidator(input);
      violations.addAll(tariffVal.validate(candidate));

      List<PlanViolation> sortedViolations =
          violations.stream().distinct().sorted(VIOLATION_ORDER).toList();

      return ValidationReport.createCapped(
          sortedViolations,
          input.maxViolationsReportCap(),
          candidate.vehicleSchedules().size(),
          candidate.totalAssignedTrips(),
          candidate.totalChargingSessions());
    } catch (RuntimeException ex) {
      return failClosedReport(candidate, ex);
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
