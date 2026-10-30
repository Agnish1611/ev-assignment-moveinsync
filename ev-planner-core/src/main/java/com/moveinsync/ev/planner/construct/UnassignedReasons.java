package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.List;

/** Turns a trip no cab can take into a stable backup reason. */
final class UnassignedReasons {

  static final String DEADLINE = "deadline reached before this trip was inserted";

  static void park(
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      UnassignedReasonCode code,
      String explanation) {
    for (Trip trip : remaining) {
      unassigned.add(UnassignedTrip.of(trip, code, explanation));
    }
    remaining.clear();
  }

  static void parkBlocked(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      List<Trip> remaining,
      List<UnassignedTrip> unassigned,
      PlugCalendar plugs) {
    List<Trip> blocked = new ArrayList<>(remaining);
    remaining.clear();
    for (Trip trip : blocked) {
      unassigned.add(explain(input, config, cursors, trip, plugs));
    }
  }

  static UnassignedTrip explain(
      PlannerInput input,
      SolverConfig config,
      List<CabCursor> cursors,
      Trip trip,
      PlugCalendar plugs) {
    int floorPercent = config.planningFloorPercent();
    if (intrinsic(input, trip, floorPercent)) {
      return UnassignedTrip.of(
          trip,
          UnassignedReasonCode.INTRINSICALLY_INFEASIBLE,
          "no cab can serve this trip even from a full battery");
    }
    int route = 0;
    int energy = 0;
    int reserve = 0;
    int time = 0;
    int congested = 0;
    for (CabCursor cursor : cursors) {
      AppendProbe.Block block = cursor.offerCharged(input, trip, floorPercent, plugs).block();
      if (block == AppendProbe.Block.ROUTE) {
        route++;
      } else if (block == AppendProbe.Block.ENERGY) {
        energy++;
      } else if (block == AppendProbe.Block.RESERVE) {
        reserve++;
      } else if (block == AppendProbe.Block.TIME) {
        time++;
      } else if (block == AppendProbe.Block.CONGESTED) {
        congested++;
      }
    }
    if (congested > 0) {
      return UnassignedTrip.of(
          trip,
          UnassignedReasonCode.CHARGER_CONGESTION,
          "a cab could take this trip if one more plug were free");
    }
    UnassignedReasonCode code = code(route, energy, reserve, time);
    return UnassignedTrip.of(trip, code, explanation(code));
  }

  private static boolean intrinsic(PlannerInput input, Trip trip, int floorPercent) {
    for (CabShift cab : input.cabs()) {
      if (RoutePhysics.fullBatteryCanServe(input, cab.vehicle(), trip, floorPercent)) {
        return false;
      }
    }
    return true;
  }

  private static UnassignedReasonCode code(int route, int energy, int reserve, int time) {
    int best = time;
    UnassignedReasonCode chosen = UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW;
    if (reserve > best) {
      best = reserve;
      chosen = UnassignedReasonCode.RESERVE_TO_HUB_VIOLATION;
    }
    if (energy > best) {
      best = energy;
      chosen = UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE;
    }
    if (route > best) {
      chosen = UnassignedReasonCode.OTHER;
    }
    return chosen;
  }

  private static String explanation(UnassignedReasonCode code) {
    if (code == UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW) {
      return "no cab could reach the pickup before the trip start";
    }
    if (code == UnassignedReasonCode.RESERVE_TO_HUB_VIOLATION) {
      return "no cab kept enough charge to reach a hub after the trip";
    }
    if (code == UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE) {
      return "no cab had enough charge to complete the trip";
    }
    return "no route from a cab to the pickup";
  }
}
