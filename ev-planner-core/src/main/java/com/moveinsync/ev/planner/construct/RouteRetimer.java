package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rebuilds one cab with trip assignments frozen. A charge stop moves only when {@link SessionShift}
 * finds a strictly cheaper contiguous block.
 *
 * <p>Time O(A · H · P · K) for A activities on the cab. Space O(A). On any failed replay the caller
 * keeps the cab's original schedule.
 */
final class RouteRetimer {

  record Draft(VehicleSchedule schedule, List<FlowJobs.FlowJob> jobs) {

    Draft {
      jobs = List.copyOf(jobs);
    }
  }

  static Optional<Draft> retime(
      CabShift shift,
      VehicleSchedule schedule,
      PlannerInput input,
      int floorPercent,
      PlugCalendar plugs) {
    CabCursor cursor = new CabCursor(shift);
    List<FlowJobs.FlowJob> jobs = new ArrayList<>();
    List<RouteActivity> activities = schedule.activities();
    for (int index = 0; index < activities.size(); index++) {
      RouteActivity activity = activities.get(index);
      if (activity instanceof ChargingSession session) {
        if (!shiftSession(cursor, input, activities, index, session, floorPercent, plugs, jobs)) {
          return Optional.empty();
        }
      } else if (activity instanceof TripAssignment assignment) {
        if (!shiftTrip(cursor, input, assignment, floorPercent, plugs)) {
          return Optional.empty();
        }
      } else {
        return Optional.empty();
      }
    }
    return Optional.of(new Draft(cursor.toSchedule(), jobs));
  }

  private static boolean shiftSession(
      CabCursor cursor,
      PlannerInput input,
      List<RouteActivity> activities,
      int index,
      ChargingSession session,
      int floorPercent,
      PlugCalendar plugs,
      List<FlowJobs.FlowJob> jobs) {
    if (index + 1 >= activities.size()
        || !(activities.get(index + 1) instanceof TripAssignment next)) {
      SessionReplay.adopt(cursor, input, session, plugs);
      return true;
    }
    Optional<ChargingSession> chosen =
        SessionShift.select(cursor, input, next.trip(), session, floorPercent, plugs);
    if (chosen.isEmpty()) {
      return false;
    }
    ChargingSession sessionToKeep = chosen.orElseThrow();
    jobs.add(FlowJobs.capture(cursor, input, next.trip(), sessionToKeep));
    SessionReplay.adopt(cursor, input, sessionToKeep, plugs);
    return true;
  }

  private static boolean shiftTrip(
      CabCursor cursor,
      PlannerInput input,
      TripAssignment assignment,
      int floorPercent,
      PlugCalendar plugs) {
    AppendProbe probe = cursor.offer(input, assignment.trip(), floorPercent);
    if (!probe.feasible()) {
      return false;
    }
    cursor.commit(input, probe, plugs);
    return true;
  }
}
