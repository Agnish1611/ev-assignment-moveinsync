package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Trip-to-cab differences between the plan being repaired and a candidate. */
final class AssignmentDiff {

  static long changes(FleetPlan before, FleetPlan after) {
    return between(vehicles(before), vehicles(after));
  }

  static long changes(FleetPlan before, List<CabCursor> after) {
    return between(vehicles(before), vehicles(after));
  }

  private static long between(Map<String, String> left, Map<String, String> right) {
    Set<String> ids = new HashSet<>();
    ids.addAll(left.keySet());
    ids.addAll(right.keySet());
    long count = 0L;
    for (String id : ids) {
      if (!left.getOrDefault(id, "").equals(right.getOrDefault(id, ""))) {
        count++;
      }
    }
    return count;
  }

  static Map<String, String> vehicles(FleetPlan plan) {
    Map<String, String> found = new HashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment) {
          found.put(assignment.trip().id().value(), schedule.vehicleId().value());
        }
      }
    }
    return found;
  }

  private static Map<String, String> vehicles(List<CabCursor> cursors) {
    Map<String, String> found = new HashMap<>();
    for (CabCursor cursor : cursors) {
      for (RouteActivity activity : cursor.toSchedule().activities()) {
        if (activity instanceof TripAssignment assignment) {
          found.put(assignment.trip().id().value(), cursor.vehicleId());
        }
      }
    }
    return found;
  }
}
