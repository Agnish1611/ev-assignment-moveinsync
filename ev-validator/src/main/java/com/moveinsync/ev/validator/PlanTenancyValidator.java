package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.List;

/** Validates cross-tenant boundaries across ground truth and candidate plans. */
final class PlanTenancyValidator {

  private PlanTenancyValidator() {}

  static void checkTenants(
      ValidationInput input, FleetPlan candidate, List<PlanViolation> violations) {
    checkPlanTenant(input, candidate, violations);
    checkGroundTruthTenants(input, violations);
    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      checkVehicleTenant(input, schedule, violations);
      checkActivityTenants(input, schedule, violations);
    }
    for (UnassignedTrip unassigned : candidate.unassignedTrips()) {
      checkUnassignedTenant(input, unassigned, violations);
    }
  }

  private static void checkGroundTruthTenants(
      ValidationInput input, List<PlanViolation> violations) {
    TenantId expected = input.tenantId();
    for (Vehicle vehicle : input.vehicles().values()) {
      if (!expected.equals(vehicle.tenantId())) {
        violations.add(vehicleMismatch(vehicle, expected, input));
      }
    }
    for (Trip trip : input.trips().values()) {
      if (!expected.equals(trip.tenantId())) {
        violations.add(tripMismatch(trip, expected));
      }
    }
    for (Hub hub : input.hubs().values()) {
      if (!expected.equals(hub.tenantId())) {
        violations.add(hubMismatch(hub, expected, input));
      }
    }
    for (Charger charger : input.chargers().values()) {
      if (!expected.equals(charger.tenantId())) {
        violations.add(chargerMismatch(charger, expected, input));
      }
    }
  }

  private static PlanViolation vehicleMismatch(
      Vehicle vehicle, TenantId expected, ValidationInput input) {
    return PlanViolation.ofVehicle(
        RuleId.TENANT_MISMATCH,
        vehicle.id(),
        input.horizonStart(),
        "Vehicle "
            + vehicle.id()
            + " tenant ("
            + vehicle.tenantId().value()
            + ") does not match input tenant ("
            + expected.value()
            + ")");
  }

  private static PlanViolation tripMismatch(Trip trip, TenantId expected) {
    return PlanViolation.ofTrip(
        RuleId.TENANT_MISMATCH,
        trip.id(),
        null,
        trip.startTs(),
        "Trip "
            + trip.id()
            + " tenant ("
            + trip.tenantId().value()
            + ") does not match input tenant ("
            + expected.value()
            + ")");
  }

  private static PlanViolation hubMismatch(Hub hub, TenantId expected, ValidationInput input) {
    return PlanViolation.ofHub(
        RuleId.TENANT_MISMATCH,
        hub.id(),
        input.horizonStart(),
        "Hub "
            + hub.id()
            + " tenant ("
            + hub.tenantId().value()
            + ") does not match input tenant ("
            + expected.value()
            + ")");
  }

  private static PlanViolation chargerMismatch(
      Charger charger, TenantId expected, ValidationInput input) {
    return PlanViolation.ofHub(
        RuleId.TENANT_MISMATCH,
        charger.hubId(),
        input.horizonStart(),
        "Charger "
            + charger.id()
            + " tenant ("
            + charger.tenantId().value()
            + ") does not match input tenant ("
            + expected.value()
            + ")");
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
    if (!input.tenantId().equals(schedule.vehicle().tenantId())) {
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
      if (activity instanceof TripAssignment assign
          && !input.tenantId().equals(assign.trip().tenantId())) {
        violations.add(assignedTripMismatch(input, schedule, assign));
      }
    }
  }

  private static PlanViolation assignedTripMismatch(
      ValidationInput input, VehicleSchedule schedule, TripAssignment assign) {
    return PlanViolation.ofTrip(
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
            + ")");
  }

  private static void checkUnassignedTenant(
      ValidationInput input, UnassignedTrip unassigned, List<PlanViolation> violations) {
    if (!input.tenantId().equals(unassigned.trip().tenantId())) {
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
