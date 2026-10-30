package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Valid lower bound on backup trips, plus a cheapest-tariff floor on grid energy.
 *
 * <p>The backup count is the maximum of three relaxations: trips no full battery can serve, the
 * peak number of overlapping trips above the cab count, and the trips that do not fit in initial
 * battery plus charger throughput when each trip is charged only its loaded distance and a reserve
 * leg. Deadhead is omitted, so a real plan can only need more energy. Time O(T · C · H + T log T)
 * for T trips, C cabs, and H hubs. Space O(T).
 */
public record FleetLowerBound(
    int intrinsicBackups, int overlapBackups, int energyBackups, long minimumCostMicros) {

  private static final int FLOOR_PERCENT = Vehicle.STANDARD_FLOOR_PERCENT;

  public FleetLowerBound {
    if (intrinsicBackups < 0 || overlapBackups < 0 || energyBackups < 0) {
      throw new IllegalArgumentException("backup counts must be non-negative");
    }
    if (minimumCostMicros < 0L) {
      throw new IllegalArgumentException(
          "minimumCostMicros must be non-negative: " + minimumCostMicros);
    }
  }

  /** Backup trips no feasible plan can avoid. */
  public int backups() {
    return Math.max(intrinsicBackups, Math.max(overlapBackups, energyBackups));
  }

  /**
   * Evaluates the bound for one shift.
   *
   * @param input ground truth for the shift
   * @return intrinsic, overlap, and energy backup counts, and the tariff floor
   */
  public static FleetLowerBound of(PlannerInput input) {
    Objects.requireNonNull(input, "input must not be null");
    int intrinsic = 0;
    List<Long> needs = new ArrayList<>();
    for (Trip trip : input.trips()) {
      long need = minimumNeed(input, trip);
      if (need < 0L) {
        intrinsic++;
      } else {
        needs.add(need);
      }
    }
    needs.sort(Comparator.naturalOrder());
    long battery = batterySupply(input);
    Pack pack = pack(needs, Math.addExact(battery, chargerSupply(input)));
    int energy = intrinsic + needs.size() - pack.count();
    return new FleetLowerBound(
        intrinsic, overlap(input), energy, price(input, pack.usedWh(), battery));
  }

  private static Pack pack(List<Long> needs, long supply) {
    long used = 0L;
    int packed = 0;
    for (long need : needs) {
      if (used > supply || need > supply - used) {
        break;
      }
      used = Math.addExact(used, need);
      packed++;
    }
    return new Pack(used, packed);
  }

  private static long price(PlannerInput input, long packedWh, long batteryWh) {
    long forced = packedWh > batteryWh ? packedWh - batteryWh : 0L;
    long rate = cheapestRate(input);
    if (forced == 0L || rate == 0L) {
      return 0L;
    }
    if (forced > Long.MAX_VALUE / rate) {
      return Long.MAX_VALUE / 1000L;
    }
    return Math.multiplyExact(forced, rate) / 1000L;
  }

  private static long cheapestRate(PlannerInput input) {
    long cheapest = 0L;
    boolean found = false;
    for (TariffSchedule schedule : input.tariffs()) {
      for (UtcTariffWindow window : schedule.windows()) {
        if (!found || window.ratePerKwhMicros() < cheapest) {
          cheapest = window.ratePerKwhMicros();
          found = true;
        }
      }
    }
    return cheapest;
  }

  private static int overlap(PlannerInput input) {
    List<Mark> marks = new ArrayList<>();
    for (Trip trip : input.trips()) {
      marks.add(new Mark(trip.startTs().getEpochSecond(), 1));
      marks.add(new Mark(trip.plannedDropoffTs().getEpochSecond(), -1));
    }
    marks.sort(Comparator.comparingLong(Mark::time).thenComparingInt(Mark::delta));
    int concurrent = 0;
    int peak = 0;
    for (Mark mark : marks) {
      concurrent += mark.delta();
      if (concurrent > peak) {
        peak = concurrent;
      }
    }
    int spare = peak - input.cabs().size();
    if (spare < 0) {
      return 0;
    }
    return spare;
  }

  private static long batterySupply(PlannerInput input) {
    long total = 0L;
    for (CabShift cab : input.cabs()) {
      long floor = cab.vehicle().calculateFloorSocWh(FLOOR_PERCENT);
      if (cab.initialSocWh() > floor) {
        total = Math.addExact(total, cab.initialSocWh() - floor);
      }
    }
    return total;
  }

  private static long chargerSupply(PlannerInput input) {
    long seconds = Duration.between(input.horizonStart(), input.horizonEnd()).getSeconds();
    long total = 0L;
    for (Charger charger : input.chargers()) {
      long wattSeconds = Math.multiplyExact((long) charger.powerKw() * 1000L, seconds);
      total = Math.addExact(total, wattSeconds / 3600L);
    }
    return total;
  }

  private static long minimumNeed(PlannerInput input, Trip trip) {
    long best = -1L;
    for (CabShift cab : input.cabs()) {
      Vehicle vehicle = cab.vehicle();
      if (!RoutePhysics.fullBatteryCanServe(input, vehicle, trip, FLOOR_PERCENT)) {
        continue;
      }
      long reserve = reserveWh(input, vehicle, trip);
      if (reserve < 0L) {
        continue;
      }
      long need =
          Math.addExact(
              RoutePhysics.energy(input, vehicle, trip.distanceMeters(), trip.startTs()), reserve);
      if (best < 0L || need < best) {
        best = need;
      }
    }
    return best;
  }

  private static long reserveWh(PlannerInput input, Vehicle vehicle, Trip trip) {
    long best = -1L;
    for (Hub hub : input.hubs()) {
      Optional<DistanceDuration> leg = travel(input, trip.toLocation(), hub.location());
      if (leg.isEmpty()) {
        continue;
      }
      long energy =
          RoutePhysics.energy(
              input, vehicle, leg.orElseThrow().distanceMeters(), trip.plannedDropoffTs());
      if (best < 0L || energy < best) {
        best = energy;
      }
    }
    return best;
  }

  private static Optional<DistanceDuration> travel(PlannerInput input, Location from, Location to) {
    if (from.id().equals(to.id())) {
      return Optional.of(DistanceDuration.zero());
    }
    return input.distanceProvider().findDistanceAndDuration(from, to);
  }

  private record Pack(long usedWh, int count) {}

  private record Mark(long time, int delta) {}
}
