package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.operator.DestroyedPlan;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Lifts trips that start after the lock window, then simulates the routes that remain.
 *
 * <p>The quota is at least one eligible trip and at most one quarter of them. Time O(T · C · H)
 * because each surviving route is simulated again. Space O(T + C). The clock is read once. A frozen
 * trip that would no longer fit is left on the original plan and nothing is lifted.
 */
final class TripRemoval {

  enum Style {
    RANDOM,
    WORST,
    SHAW
  }

  static DestroyedPlan remove(
      PlannerInput input,
      FleetPlan plan,
      SolverConfig config,
      SplittableRandom random,
      Clock clock,
      Style style) {
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(config, "config must not be null");
    Objects.requireNonNull(random, "random must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    if (!plan.parsedTenantId().equals(input.tenantId())) {
      throw new IllegalArgumentException("plan tenant does not match planner tenant");
    }
    Instant now = clock.instant();
    if (!now.isBefore(config.deadline())) {
      return new DestroyedPlan(plan, List.of());
    }
    List<Assigned> eligible = eligible(input, plan, now, config.lockWindowSeconds());
    int quota = quota(eligible.size(), random);
    Set<String> drop = quota == 0 ? Set.of() : ids(pick(eligible, quota, random, style));
    RouteReplay.Outcome outcome =
        RouteReplay.relax(
            input,
            plan,
            config.planningFloorPercent(),
            drop,
            trip -> frozen(trip, now, config.lockWindowSeconds()),
            new PlugCalendar());
    if (outcome.aborted()) {
      return new DestroyedPlan(plan, List.of());
    }
    return finish(input, plan, outcome);
  }

  static boolean frozen(Trip trip, Instant now, int lockWindowSeconds) {
    Instant lockEnd = now.plusSeconds(lockWindowSeconds);
    return !trip.startTs().isAfter(lockEnd);
  }

  private static DestroyedPlan finish(
      PlannerInput input, FleetPlan plan, RouteReplay.Outcome outcome) {
    List<Trip> lifted = sorted(outcome.lifted());
    FleetPlan rebuilt =
        PlanAssembler.assemble(
            input, plan.planId(), plan.seed(), outcome.cursors(), merge(plan, lifted));
    List<TripId> ids = new ArrayList<>();
    for (Trip trip : lifted) {
      ids.add(trip.id());
    }
    return new DestroyedPlan(rebuilt, ids);
  }

  private static List<Assigned> eligible(
      PlannerInput input, FleetPlan plan, Instant now, int lockWindowSeconds) {
    List<Assigned> open = new ArrayList<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment) {
          consider(input, schedule, assignment, now, lockWindowSeconds, open);
        }
      }
    }
    return open;
  }

  private static void consider(
      PlannerInput input,
      VehicleSchedule schedule,
      TripAssignment assignment,
      Instant now,
      int lockWindowSeconds,
      List<Assigned> open) {
    Trip trip = RouteReplay.requireTrip(input, assignment.trip().id());
    if (frozen(trip, now, lockWindowSeconds)) {
      return;
    }
    long loaded =
        RoutePhysics.energy(input, schedule.vehicle(), trip.distanceMeters(), trip.startTs());
    open.add(new Assigned(trip, Math.addExact(assignment.deadheadEnergyWh(), loaded)));
  }

  private static int quota(int eligible, SplittableRandom random) {
    if (eligible <= 0) {
      return 0;
    }
    int upper = Math.max(1, eligible / 4);
    return 1 + random.nextInt(upper);
  }

  private static List<Trip> pick(
      List<Assigned> eligible, int quota, SplittableRandom random, Style style) {
    if (style == Style.WORST) {
      return worst(eligible, quota);
    }
    if (style == Style.SHAW) {
      return shaw(eligible, quota, random);
    }
    return randomPick(eligible, quota, random);
  }

  private static List<Trip> randomPick(
      List<Assigned> eligible, int quota, SplittableRandom random) {
    List<Trip> copy = trips(eligible);
    for (int i = copy.size() - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      Trip swap = copy.get(i);
      copy.set(i, copy.get(j));
      copy.set(j, swap);
    }
    return List.copyOf(copy.subList(0, quota));
  }

  private static List<Trip> worst(List<Assigned> eligible, int quota) {
    List<Assigned> ranked = new ArrayList<>(eligible);
    ranked.sort(
        Comparator.comparingLong(Assigned::energyWh)
            .reversed()
            .thenComparing(assigned -> assigned.trip().id().value()));
    List<Trip> chosen = new ArrayList<>();
    for (int i = 0; i < quota; i++) {
      chosen.add(ranked.get(i).trip());
    }
    return chosen;
  }

  private static List<Trip> shaw(List<Assigned> eligible, int quota, SplittableRandom random) {
    List<Trip> trips = trips(eligible);
    Trip seed = trips.get(random.nextInt(trips.size()));
    List<Trip> rest = new ArrayList<>();
    for (Trip trip : trips) {
      if (!trip.id().equals(seed.id())) {
        rest.add(trip);
      }
    }
    rest.sort(
        Comparator.comparingLong((Trip trip) -> startGap(seed, trip))
            .thenComparing(trip -> trip.id().value()));
    List<Trip> chosen = new ArrayList<>();
    chosen.add(seed);
    int extra = quota - 1;
    for (int i = 0; i < extra && i < rest.size(); i++) {
      chosen.add(rest.get(i));
    }
    return chosen;
  }

  private static long startGap(Trip seed, Trip trip) {
    long left = seed.startTs().getEpochSecond();
    long right = trip.startTs().getEpochSecond();
    return left >= right ? left - right : right - left;
  }

  private static List<Trip> trips(List<Assigned> eligible) {
    List<Trip> trips = new ArrayList<>();
    for (Assigned assigned : eligible) {
      trips.add(assigned.trip());
    }
    return trips;
  }

  private static Set<String> ids(List<Trip> trips) {
    Set<String> ids = new HashSet<>();
    for (Trip trip : trips) {
      ids.add(trip.id().value());
    }
    return ids;
  }

  private static List<Trip> sorted(List<Trip> trips) {
    List<Trip> copy = new ArrayList<>(trips);
    copy.sort(Comparator.comparing(Trip::startTs).thenComparing(trip -> trip.id().value()));
    return copy;
  }

  private static List<UnassignedTrip> merge(FleetPlan plan, List<Trip> lifted) {
    Set<String> liftedIds = ids(lifted);
    List<UnassignedTrip> unassigned = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (UnassignedTrip existing : plan.unassignedTrips()) {
      String id = existing.trip().id().value();
      if (!liftedIds.contains(id) && seen.add(id)) {
        unassigned.add(existing);
      }
    }
    for (Trip trip : lifted) {
      if (seen.add(trip.id().value())) {
        unassigned.add(
            UnassignedTrip.of(
                trip, UnassignedReasonCode.OTHER, "lifted so it can be inserted again"));
      }
    }
    unassigned.sort(
        Comparator.comparing((UnassignedTrip item) -> item.trip().startTs())
            .thenComparing(item -> item.trip().id().value()));
    return unassigned;
  }

  private record Assigned(Trip trip, long energyWh) {}
}
