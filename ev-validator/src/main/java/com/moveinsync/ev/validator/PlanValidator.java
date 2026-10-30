package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Independent plan validation engine for electric vehicle fleet charging and trip assignments.
 *
 * <p>Enforces the non-negotiable hard invariants H1 through H6:
 *
 * <ul>
 *   <li><b>H1:</b> Battery SoC never below 15% safety floor at any point, including deadheads.
 *   <li><b>H2:</b> Concurrent charging sessions at a hub never exceed physical charger capacity at
 *       any second (evaluated via continuous event sweep, independent of discrete solver grids).
 *   <li><b>H3:</b> Every assigned trip starts on time and finishes by its latest allowed finish.
 *   <li><b>H4:</b> Each trip assigned to at most one cab; a cab never does two things at once.
 *   <li><b>H5:</b> Reserve-to-hub: after every trip the cab can still reach a charger above the
 *       floor.
 *   <li><b>H6:</b> Charging session duration strictly matches or exceeds the two-segment curve.
 * </ul>
 *
 * <p>Architectural Rule: As mandated by AGENTS.md, this validator depends strictly and solely on
 * {@code ev-domain} and standard Java libraries. It has zero coupling with {@code ev-planner-core}
 * or {@code ev-service}.
 *
 * <p>Complexity:
 *
 * <ul>
 *   <li>Time complexity: O(S log S + T * H + C * L), where S is charging sessions, T is trips, H is
 *       hubs, C is cabs, and L is average route length.
 *   <li>Space complexity: O(S + T + C * L).
 * </ul>
 */
public final class PlanValidator {

  private final ValidationContext context;
  private final BatteryFloorValidator batteryValidator;
  private final ChargerCapacityValidator capacityValidator;
  private final TripTimingValidator timingValidator;
  private final AssignmentValidator assignmentValidator;
  private final ReserveToHubValidator reserveValidator;
  private final ChargingCurveValidator curveValidator;

  public PlanValidator(ValidationContext context) {
    this.context = Objects.requireNonNull(context, "ValidationContext must not be null");
    this.batteryValidator = new BatteryFloorValidator(context);
    this.capacityValidator = new ChargerCapacityValidator(context);
    this.timingValidator = new TripTimingValidator();
    this.assignmentValidator = new AssignmentValidator(context);
    this.reserveValidator = new ReserveToHubValidator(context);
    this.curveValidator = new ChargingCurveValidator(context);
  }

  /**
   * Performs an exhaustive independent validation check of the candidate fleet plan.
   *
   * @param plan the candidate plan to validate
   * @return structured validation report detailing all findings
   */
  public ValidationReport validate(FleetPlan plan) {
    Objects.requireNonNull(plan, "FleetPlan must not be null");

    List<PlanViolation> allViolations = new ArrayList<>();

    // 1. Invariant H1: Battery Floor & Energy Balance
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      allViolations.addAll(batteryValidator.validate(schedule));
    }

    // 2. Invariant H2: Charger Capacity (Continuous Event Sweep)
    allViolations.addAll(capacityValidator.validate(plan));

    // 3. Invariant H3: Trip Timing & Deadlines
    allViolations.addAll(timingValidator.validate(plan));

    // 4. Invariant H4: Single Assignment & Single Cab No-Overlap
    allViolations.addAll(assignmentValidator.validate(plan));

    // 5. Invariant H5: Reserve-to-Hub Invariant
    allViolations.addAll(reserveValidator.validate(plan));

    // 6. Invariant H6: Two-Segment Charging Curve Matching
    allViolations.addAll(curveValidator.validate(plan));

    int totalTripsChecked = plan.totalAssignedTrips();
    int totalSessionsChecked = plan.totalChargingSessions();
    int totalVehiclesChecked = plan.vehicleSchedules().size();

    return new ValidationReport(
        allViolations, totalVehiclesChecked, totalTripsChecked, totalSessionsChecked);
  }
}
