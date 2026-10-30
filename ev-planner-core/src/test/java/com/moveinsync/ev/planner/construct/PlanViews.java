package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads assignments out of a plan. Vehicle schedules do not implement value equality. */
final class PlanViews {

  private PlanViews() {}

  static Map<String, TripAssignment> byTrip(FleetPlan plan) {
    Map<String, TripAssignment> found = new LinkedHashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment) {
          found.put(assignment.trip().id().value(), assignment);
        }
      }
    }
    return found;
  }

  static FleetPlan withActivities(PlannerInput input, List<RouteActivity> activities) {
    CabShift cab = input.cabs().get(0);
    VehicleSchedule schedule =
        new VehicleSchedule(
            cab.vehicle(),
            cab.startLocation(),
            cab.initialSocWh(),
            cab.availableFrom(),
            activities);
    return new FleetPlan(
        "hand",
        input.tenantId(),
        input.horizonStart(),
        input.horizonEnd(),
        List.of(schedule),
        List.of(),
        1L,
        input.inputHash());
  }
}
