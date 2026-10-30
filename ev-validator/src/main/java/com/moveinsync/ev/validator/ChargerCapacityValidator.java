package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates invariant H2: Concurrent charging sessions at a hub never exceed its physical charger
 * count at any second.
 *
 * <p>Implemented via an exact continuous event-sweep algorithm, completely independent of the
 * planner's discrete timeline resolution.
 */
final class ChargerCapacityValidator {

  private record SweepEvent(Instant time, int delta, ChargingSession session) {}

  private final ValidationContext context;

  ChargerCapacityValidator(ValidationContext context) {
    this.context = context;
  }

  List<PlanViolation> validate(FleetPlan plan) {
    List<PlanViolation> violations = new ArrayList<>();
    Map<HubId, List<ChargingSession>> sessionsByHub = collectSessionsByHub(plan);

    for (Map.Entry<HubId, List<ChargingSession>> entry : sessionsByHub.entrySet()) {
      HubId hubId = entry.getKey();
      List<ChargingSession> sessions = entry.getValue();

      Optional<Hub> hubOpt = context.findHub(hubId);
      if (hubOpt.isEmpty()) {
        violations.add(
            PlanViolation.ofHub(
                RuleId.H2_CHARGER_CAPACITY,
                hubId,
                sessions.get(0).startTime(),
                "Charging sessions assigned to unregistered hub: " + hubId));
        continue;
      }

      int capacity = hubOpt.get().chargerCount();
      validatePlugConflicts(sessions, hubId, violations);
      runEventSweep(sessions, hubId, capacity, violations);
    }

    return violations;
  }

  private Map<HubId, List<ChargingSession>> collectSessionsByHub(FleetPlan plan) {
    Map<HubId, List<ChargingSession>> map = new HashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          map.computeIfAbsent(session.hubId(), k -> new ArrayList<>()).add(session);
        }
      }
    }
    return map;
  }

  private void validatePlugConflicts(
      List<ChargingSession> sessions, HubId hubId, List<PlanViolation> violations) {
    Map<ChargerId, List<ChargingSession>> byCharger = new HashMap<>();
    for (ChargingSession s : sessions) {
      byCharger.computeIfAbsent(s.chargerId(), k -> new ArrayList<>()).add(s);
    }

    for (Map.Entry<ChargerId, List<ChargingSession>> entry : byCharger.entrySet()) {
      List<ChargingSession> plugSessions = entry.getValue();
      plugSessions.sort(Comparator.comparing(ChargingSession::startTime));

      for (int i = 0; i < plugSessions.size() - 1; i++) {
        ChargingSession first = plugSessions.get(i);
        ChargingSession second = plugSessions.get(i + 1);
        if (first.overlaps(second)) {
          violations.add(
              PlanViolation.ofHub(
                  RuleId.H2_CHARGER_CAPACITY,
                  hubId,
                  second.startTime(),
                  "Plug conflict on charger "
                      + entry.getKey()
                      + " at hub "
                      + hubId
                      + ": session "
                      + first.sessionId()
                      + " ["
                      + first.startTime()
                      + ", "
                      + first.endTime()
                      + ") overlaps session "
                      + second.sessionId()
                      + " ["
                      + second.startTime()
                      + ", "
                      + second.endTime()
                      + ")"));
        }
      }
    }
  }

  private void runEventSweep(
      List<ChargingSession> sessions, HubId hubId, int capacity, List<PlanViolation> violations) {
    List<SweepEvent> events = new ArrayList<>(sessions.size() * 2);
    for (ChargingSession session : sessions) {
      events.add(new SweepEvent(session.startTime(), 1, session));
      events.add(new SweepEvent(session.endTime(), -1, session));
    }

    // Sort by timestamp; at identical timestamps, end events (-1) precede start events (+1)
    events.sort(Comparator.comparing(SweepEvent::time).thenComparingInt(SweepEvent::delta));

    int currentOccupancy = 0;
    Instant breachTime = null;

    for (SweepEvent event : events) {
      currentOccupancy += event.delta;
      if (currentOccupancy > capacity) {
        if (breachTime == null || !breachTime.equals(event.time())) {
          breachTime = event.time();
          violations.add(
              PlanViolation.ofHub(
                  RuleId.H2_CHARGER_CAPACITY,
                  hubId,
                  event.time(),
                  "Hub "
                      + hubId
                      + " concurrent charging sessions ("
                      + currentOccupancy
                      + ") exceeds physical charger capacity ("
                      + capacity
                      + ") at "
                      + event.time()));
        }
      }
    }
  }
}
