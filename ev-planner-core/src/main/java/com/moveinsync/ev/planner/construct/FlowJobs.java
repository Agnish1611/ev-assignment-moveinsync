package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Feasible hub windows for one published charging session. The session interval is included. */
final class FlowJobs {

  record Opening(String hubId, long earliestInclusive, long latestExclusive) {}

  record FlowJob(
      String jobId, String vehicleId, long energyWh, int maxChargeKw, List<Opening> openings) {

    FlowJob {
      openings = List.copyOf(openings);
    }
  }

  static FlowJob capture(CabCursor cursor, PlannerInput input, Trip trip, ChargingSession chosen) {
    List<Opening> openings = new ArrayList<>();
    for (Hub hub : input.hubs()) {
      Optional<Opening> opening = opening(cursor, input, trip, hub);
      if (opening.isPresent()) {
        openings.add(opening.orElseThrow());
      }
    }
    widen(openings, chosen);
    return new FlowJob(
        chosen.sessionId(),
        cursor.vehicleId(),
        Math.max(0L, chosen.energyDeliveredWh()),
        cursor.vehicle().maxChargeKw(),
        openings);
  }

  private static Optional<Opening> opening(
      CabCursor cursor, PlannerInput input, Trip trip, Hub hub) {
    Optional<DistanceDuration> inbound =
        RoutePhysics.travel(input, cursor.location(), hub.location());
    Optional<DistanceDuration> outbound =
        RoutePhysics.travel(input, hub.location(), trip.fromLocation());
    if (inbound.isEmpty() || outbound.isEmpty()) {
      return Optional.empty();
    }
    long earliest = cursor.freeAt().getEpochSecond() + inbound.orElseThrow().durationSeconds();
    long latest = trip.startTs().getEpochSecond() - outbound.orElseThrow().durationSeconds();
    long horizonStart = input.horizonStart().getEpochSecond();
    long horizonEnd = input.horizonEnd().getEpochSecond();
    if (earliest < horizonStart) {
      earliest = horizonStart;
    }
    if (latest > horizonEnd) {
      latest = horizonEnd;
    }
    if (earliest >= latest) {
      return Optional.empty();
    }
    return Optional.of(new Opening(hub.id().value(), earliest, latest));
  }

  private static void widen(List<Opening> openings, ChargingSession chosen) {
    long start = chosen.startTime().getEpochSecond();
    long end = chosen.endTime().getEpochSecond();
    String hubId = chosen.hubId().value();
    for (int index = 0; index < openings.size(); index++) {
      Opening opening = openings.get(index);
      if (!opening.hubId().equals(hubId)) {
        continue;
      }
      long earliest = Math.min(opening.earliestInclusive(), start);
      long latest = Math.max(opening.latestExclusive(), end);
      openings.set(index, new Opening(hubId, earliest, latest));
      return;
    }
    openings.add(new Opening(hubId, start, end));
  }
}
