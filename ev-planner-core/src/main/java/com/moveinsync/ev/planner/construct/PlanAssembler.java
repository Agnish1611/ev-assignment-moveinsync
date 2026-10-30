package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.List;

/** Builds a fleet plan from cab cursors. The seed and input hash are stored on the plan. */
final class PlanAssembler {

  static FleetPlan assemble(
      PlannerInput input,
      String planId,
      long seed,
      List<CabCursor> cursors,
      List<UnassignedTrip> unassigned) {
    List<VehicleSchedule> schedules = new ArrayList<>();
    for (CabCursor cursor : cursors) {
      schedules.add(cursor.toSchedule());
    }
    return new FleetPlan(
        planId,
        input.tenantId(),
        input.horizonStart(),
        input.horizonEnd(),
        schedules,
        unassigned,
        seed,
        input.inputHash());
  }
}
