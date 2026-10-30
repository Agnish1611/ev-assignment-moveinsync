package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Counterfactual reasons for backup trips. Each trip is offered to every cab on the published
 * routes. When a cab is blocked only by plugs, the same offer is retried with one extra plug at
 * each hub.
 *
 * <p>Time O(U · C · H · P · S) for U backup trips, C cabs, H hubs, P plugs, and S slot attempts.
 * Space O(U · C). Evidence is watt-hours and UTC instants.
 */
public final class AssignmentDiagnosis {

  private AssignmentDiagnosis() {
    throw new IllegalStateException("utility");
  }

  public static List<TripDiagnosis> explain(
      PlannerInput input, SolverConfig config, FleetPlan plan) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    ProbeState replay = replay(input, config, plan);
    List<UnassignedTrip> rows = new ArrayList<>(plan.unassignedTrips());
    rows.sort(Comparator.comparing(row -> row.trip().id().value()));
    List<TripDiagnosis> diagnoses = new ArrayList<>();
    for (UnassignedTrip row : rows) {
      diagnoses.add(one(input, config, replay, row.trip()));
    }
    return List.copyOf(diagnoses);
  }

  private static ProbeState replay(PlannerInput input, SolverConfig config, FleetPlan plan) {
    PlugCalendar plugs = new PlugCalendar();
    List<CabCursor> cursors = RouteReplay.load(input, plan, config.planningFloorPercent(), plugs);
    return new ProbeState(cursors, plugs);
  }

  private static TripDiagnosis one(
      PlannerInput input, SolverConfig config, ProbeState replay, Trip trip) {
    if (intrinsic(input, trip, config.planningFloorPercent())) {
      return diagnosis(
          trip,
          UnassignedReasonCode.INTRINSICALLY_INFEASIBLE,
          "no cab can serve this trip even from a full battery",
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          List.of());
    }
    List<CabFinding> findings = findings(input, config, replay, trip);
    Optional<PlugWindow> window = congestion(input, config, replay, trip, findings);
    if (window.isPresent()) {
      PlugWindow open = window.orElseThrow();
      return diagnosis(
          trip,
          UnassignedReasonCode.CHARGER_CONGESTION,
          "feasible if "
              + open.hubId
              + " had one more plug from "
              + open.start
              + " until "
              + open.end,
          Optional.of(open.hubId),
          Optional.of(open.start),
          Optional.of(open.end),
          findings);
    }
    CabFinding best = best(findings);
    return diagnosis(
        trip,
        code(best.constraint()),
        explanation(best.constraint()),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        findings);
  }

  private static boolean intrinsic(PlannerInput input, Trip trip, int floorPercent) {
    for (CabShift cab : input.cabs()) {
      if (RoutePhysics.fullBatteryCanServe(input, cab.vehicle(), trip, floorPercent)) {
        return false;
      }
    }
    return true;
  }

  private static List<CabFinding> findings(
      PlannerInput input, SolverConfig config, ProbeState replay, Trip trip) {
    List<CabFinding> findings = new ArrayList<>();
    int floorPercent = config.planningFloorPercent();
    for (CabCursor cursor : replay.cursors()) {
      AppendProbe probe = cursor.offerCharged(input, trip, floorPercent, replay.plugs());
      findings.add(
          new CabFinding(
              cursor.vehicleId(),
              BindingConstraint.of(probe.block()),
              arrival(input, cursor, trip),
              cursor.vehicle().calculateFloorSocWh(floorPercent)));
    }
    return findings;
  }

  private static OptionalLong arrival(PlannerInput input, CabCursor cursor, Trip trip) {
    Optional<DistanceDuration> leg =
        RoutePhysics.travel(input, cursor.location(), trip.fromLocation());
    if (leg.isEmpty()) {
      return OptionalLong.empty();
    }
    long deadhead =
        RoutePhysics.energy(
            input, cursor.vehicle(), leg.orElseThrow().distanceMeters(), trip.startTs());
    if (deadhead > cursor.socWh()) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(cursor.socWh() - deadhead);
  }

  private static Optional<PlugWindow> congestion(
      PlannerInput input,
      SolverConfig config,
      ProbeState replay,
      Trip trip,
      List<CabFinding> findings) {
    if (!congested(findings)) {
      return Optional.empty();
    }
    List<Hub> hubs = new ArrayList<>(input.hubs());
    hubs.sort(Comparator.comparing(hub -> hub.id().value()));
    for (Hub hub : hubs) {
      Optional<PlugWindow> window = hubWindow(input, config, replay, trip, hub);
      if (window.isPresent()) {
        return window;
      }
    }
    return Optional.empty();
  }

  private static Optional<PlugWindow> hubWindow(
      PlannerInput input, SolverConfig config, ProbeState replay, Trip trip, Hub hub) {
    Optional<PlannerInput> relaxed = RelaxedFleet.oneMorePlug(input, hub);
    if (relaxed.isEmpty()) {
      return Optional.empty();
    }
    PlannerInput fleet = relaxed.orElseThrow();
    for (CabCursor cursor : replay.cursors()) {
      AppendProbe probe =
          cursor.offerCharged(fleet, trip, config.planningFloorPercent(), replay.plugs());
      Optional<PlugWindow> window = window(hub, probe);
      if (window.isPresent()) {
        return window;
      }
    }
    return Optional.empty();
  }

  private static Optional<PlugWindow> window(Hub hub, AppendProbe probe) {
    if (!probe.feasible() || probe.charge().isEmpty()) {
      return Optional.empty();
    }
    ChargingSession session = probe.charge().orElseThrow();
    if (!session.hubId().equals(hub.id())) {
      return Optional.empty();
    }
    return Optional.of(new PlugWindow(hub.id().value(), session.startTime(), session.endTime()));
  }

  private static boolean congested(List<CabFinding> findings) {
    for (CabFinding finding : findings) {
      if (finding.constraint() == BindingConstraint.CONGESTED) {
        return true;
      }
    }
    return false;
  }

  private static CabFinding best(List<CabFinding> findings) {
    if (findings.isEmpty()) {
      throw new IllegalArgumentException("a diagnosed trip needs at least one cab");
    }
    CabFinding chosen = findings.get(0);
    for (CabFinding finding : findings) {
      int byRank = Integer.compare(finding.constraint().rank(), chosen.constraint().rank());
      if (byRank > 0 || (byRank == 0 && finding.cabId().compareTo(chosen.cabId()) < 0)) {
        chosen = finding;
      }
    }
    return chosen;
  }

  private static UnassignedReasonCode code(BindingConstraint constraint) {
    return switch (constraint) {
      case TIME -> UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW;
      case ENERGY -> UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE;
      case RESERVE -> UnassignedReasonCode.RESERVE_TO_HUB_VIOLATION;
      case CONGESTED -> UnassignedReasonCode.CHARGER_CONGESTION;
      case ROUTE, FEASIBLE -> UnassignedReasonCode.OTHER;
    };
  }

  private static String explanation(BindingConstraint constraint) {
    return switch (constraint) {
      case TIME -> "no cab could reach the pickup before the trip start";
      case ENERGY -> "no cab had enough charge to complete the trip";
      case RESERVE -> "no cab kept enough charge to reach a hub after the trip";
      case CONGESTED -> "one extra plug is not enough at any hub";
      case ROUTE -> "no route from a cab to the pickup";
      case FEASIBLE -> "a cab can take this trip";
    };
  }

  private static TripDiagnosis diagnosis(
      Trip trip,
      UnassignedReasonCode code,
      String explanation,
      Optional<String> hubId,
      Optional<Instant> start,
      Optional<Instant> end,
      List<CabFinding> findings) {
    return new TripDiagnosis(trip.id().value(), code, explanation, hubId, start, end, findings);
  }

  private record PlugWindow(String hubId, Instant start, Instant end) {}

  private record ProbeState(List<CabCursor> cursors, PlugCalendar plugs) {}
}
