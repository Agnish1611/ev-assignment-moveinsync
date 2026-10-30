package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Expands one cab schedule into empty drives, trips, and charging sessions. Time and space O(A) for
 * A activities. Waits are omitted. SoC on each step is the value stored on the plan.
 */
public final class CabTimeline {

  private CabTimeline() {
    throw new IllegalStateException("utility");
  }

  public static List<TimelineStep> of(PlannerInput input, VehicleSchedule schedule) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(schedule, "schedule must not be null");
    return new Walk(input, schedule).steps();
  }

  private static final class Walk {
    private final PlannerInput input;
    private final List<TimelineStep> steps = new ArrayList<>();
    private Location here;
    private long socWh;
    private Instant freeAt;

    private Walk(PlannerInput input, VehicleSchedule schedule) {
      this.input = input;
      this.here = schedule.initialLocation();
      this.socWh = schedule.initialSocWh();
      this.freeAt = schedule.availableFrom();
      for (RouteActivity activity : schedule.activities()) {
        visit(activity);
      }
    }

    private List<TimelineStep> steps() {
      return List.copyOf(steps);
    }

    private void visit(RouteActivity activity) {
      if (activity instanceof ChargingSession session) {
        charge(session);
        return;
      }
      if (activity instanceof TripAssignment assignment) {
        trip(assignment);
      }
    }

    private void charge(ChargingSession session) {
      drive(hubLocation(session), session.startTime(), session.startSocWh());
      steps.add(
          new TimelineStep(
              TimelineStep.Kind.CHARGE,
              session.sessionId(),
              session.startTime(),
              session.endTime(),
              session.startSocWh(),
              session.targetSocWh(),
              0));
      here = hubLocation(session);
      socWh = session.targetSocWh();
      freeAt = session.endTime();
    }

    private void trip(TripAssignment assignment) {
      drive(
          assignment.trip().fromLocation(),
          assignment.pickupTime(),
          assignment.arrivalAtPickupSocWh());
      steps.add(
          new TimelineStep(
              TimelineStep.Kind.TRIP,
              assignment.trip().id().value(),
              assignment.pickupTime(),
              assignment.dropoffTime(),
              assignment.arrivalAtPickupSocWh(),
              assignment.departureAfterDropoffSocWh(),
              assignment.trip().distanceMeters()));
      here = assignment.trip().toLocation();
      socWh = assignment.departureAfterDropoffSocWh();
      freeAt = assignment.dropoffTime();
    }

    private void drive(Location next, Instant arriveBy, long socAfterWh) {
      Optional<DistanceDuration> leg = RoutePhysics.travel(input, here, next);
      if (leg.isEmpty()) {
        throw new IllegalArgumentException("no route for a timeline deadhead");
      }
      DistanceDuration travel = leg.orElseThrow();
      if (travel.distanceMeters() == 0) {
        here = next;
        return;
      }
      Instant start = depart(arriveBy, travel.durationSeconds());
      Instant end = start.plusSeconds(travel.durationSeconds());
      steps.add(
          new TimelineStep(
              TimelineStep.Kind.DEADHEAD,
              next.id().value(),
              start,
              end,
              socWh,
              socAfterWh,
              travel.distanceMeters()));
      here = next;
      socWh = socAfterWh;
      freeAt = end;
    }

    private Instant depart(Instant arriveBy, int durationSeconds) {
      Instant start = arriveBy.minusSeconds(durationSeconds);
      if (start.isBefore(freeAt)) {
        return freeAt;
      }
      return start;
    }

    private Location hubLocation(ChargingSession session) {
      for (Hub hub : input.hubs()) {
        if (hub.id().equals(session.hubId())) {
          return hub.location();
        }
      }
      throw new IllegalArgumentException("charging session hub is not in the planner input");
    }
  }
}
