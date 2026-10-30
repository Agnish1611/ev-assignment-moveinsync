package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.util.List;

/** Validates cross-tenant boundaries across candidate plans, vehicles, trips, and activities. */
final class PlanTenancyValidator {

  private PlanTenancyValidator() {}

  static void checkTenants(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    checkPlanTenant(input, candidate, violations);
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      checkVehicleTenant(input, schedule, violations);
      checkActivityTenants(input, schedule, violations);
    }
    for (UnassignedTrip unassigned : candidate.unassignedTrips()) {
      checkUnassignedTenant(input, unassigned, violations);
    }
  }

  private static void checkPlanTenant(
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
  }

  private static void checkVehicleTenant(
      ValidationInput input, VehicleSchedule schedule, List<PlanViolation> violations) {
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
  }

  private static void checkActivityTenants(
      ValidationInput input, VehicleSchedule schedule, List<PlanViolation> violations) {
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

  private static void checkUnassignedTenant(
      ValidationInput input, UnassignedTrip unassigned, List<PlanViolation> violations) {
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
