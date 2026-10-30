package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hand-built fleets for replan tests. The plan's claimed SoC is not replayed. */
final class ReplanScenes {

  record Hold(String cabId, Trip trip, long claimedSocWh) {}

  private ReplanScenes() {
    throw new IllegalStateException("utility");
  }

  static Trip trip(String id, long offsetSeconds, int meters, int durationSeconds) {
    Instant pickup = PlannerFixtures.START.plusSeconds(offsetSeconds);
    return new Trip(
        PlannerFixtures.TENANT,
        TripId.of(id),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        meters,
        durationSeconds,
        pickup.plusSeconds(durationSeconds),
        "EMP-1");
  }

  static PlannerInput fleet(List<CabShift> cabs, List<Trip> trips) {
    PlannerInput.Builder builder = PlannerFixtures.shell();
    for (CabShift cab : cabs) {
      builder.addCab(cab);
    }
    for (Trip trip : trips) {
      builder.addTrip(trip);
    }
    return builder.build();
  }

  static FleetPlan plan(PlannerInput input, String planId, List<Hold> holds) {
    return plan(input, planId, holds, List.of());
  }

  static FleetPlan plan(
      PlannerInput input, String planId, List<Hold> holds, List<UnassignedTrip> parked) {
    Map<String, List<Hold>> grouped = new LinkedHashMap<>();
    for (Hold hold : holds) {
      grouped.computeIfAbsent(hold.cabId(), key -> new ArrayList<>()).add(hold);
    }
    List<VehicleSchedule> schedules = new ArrayList<>();
    for (List<Hold> group : grouped.values()) {
      schedules.add(schedule(input, group));
    }
    return new FleetPlan(
        planId,
        input.tenantId(),
        input.horizonStart(),
        input.horizonEnd(),
        schedules,
        parked,
        1L,
        input.inputHash());
  }

  static String cabOf(FleetPlan plan, String tripId) {
    String cab = AssignmentDiff.vehicles(plan).get(tripId);
    if (cab == null) {
      return "";
    }
    return cab;
  }

  private static VehicleSchedule schedule(PlannerInput input, List<Hold> group) {
    CabShift cab = find(input, group.get(0).cabId());
    List<RouteActivity> activities = new ArrayList<>();
    for (Hold hold : group) {
      Trip trip = hold.trip();
      activities.add(
          new TripAssignment(
              trip, cab.vehicle().id(), trip.startTs(), trip.plannedDropoffTs(), 0, 0, 0L, 1L, 1L));
    }
    return new VehicleSchedule(
        cab.vehicle(),
        cab.startLocation(),
        group.get(0).claimedSocWh(),
        cab.availableFrom(),
        activities);
  }

  private static CabShift find(PlannerInput input, String cabId) {
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().value().equals(cabId)) {
        return cab;
      }
    }
    throw new IllegalArgumentException("cab is not in the fleet: " + cabId);
  }
}
