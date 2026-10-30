package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.ReplanSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Several drift events are coalesced, then applied in time and kind order. */
class ReplanBatchTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aBatchDropsAStaleReadingAndAppliesRemovalBeforeAnAdd() {
    Trip moved = ReplanScenes.trip("TRIP-MOVE", 3_600, 2_000, 1_200);
    Trip added = ReplanScenes.trip("TRIP-NEW", 7_200, 2_000, 1_200);
    CabShift removed = PlannerFixtures.cab("CAB-A", 50_000L);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-B", 50_000L)), List.of(moved, added));
    FleetPlan current = planOn(fleet, removed, moved);
    Instant at = PlannerFixtures.START;
    List<DriftEvent> events =
        List.of(
            new DriftEvent(DriftEvent.Kind.TRIP_ADDED, "TRIP-NEW", at),
            new DriftEvent(DriftEvent.Kind.CAB_REMOVED, "CAB-A", at),
            new DriftEvent(DriftEvent.Kind.LATE_CAB, "CAB-B", at.plusSeconds(1)),
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-B", at.plusSeconds(2)),
            new DriftEvent(DriftEvent.Kind.CHARGER_FAILURE, "CHG-1", at.plusSeconds(3)),
            new DriftEvent(DriftEvent.Kind.CHARGER_FAILURE, "CHG-1", at, OptionalLong.of(1L)));

    FleetPlan repaired = ReplanBatch.apply(solver, fleet, current, events, config, clock).plan();

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-MOVE")).isEqualTo("CAB-B");
    assertThat(ReplanScenes.cabOf(repaired, "TRIP-NEW")).isEqualTo("CAB-B");
    assertThat(repaired.findVehicleSchedule(VehicleId.of("CAB-A"))).isEmpty();
  }

  @Test
  void aCancellationThenABatteryReadingKeepsTheRemainingTrip() {
    Trip gone = ReplanScenes.trip("TRIP-GONE", 3_600, 2_000, 1_200);
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 7_200, 2_000, 1_200);
    PlannerInput full =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(gone, kept));
    PlannerInput reduced =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept));
    FleetPlan current =
        ReplanScenes.plan(
            full, "plan-batch", List.of(new ReplanScenes.Hold("CAB-A", gone, 50_000L)));
    Instant at = PlannerFixtures.START;
    List<DriftEvent> events =
        List.of(
            new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", at.plusSeconds(1)),
            new DriftEvent(DriftEvent.Kind.TRIP_CANCELLED, "TRIP-GONE", at));

    FleetPlan repaired = ReplanBatch.apply(solver, reduced, current, events, config, clock).plan();

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(repaired.totalUnassignedTrips()).isZero();
    assertThat(ReplanScenes.cabOf(repaired, "TRIP-GONE")).isEmpty();
  }

  @Test
  void anEqualTimestampKeepsTheFirstReading() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-tie", List.of(new ReplanScenes.Hold("CAB-A", trip, 50_000L)));
    DriftEvent first =
        new DriftEvent(DriftEvent.Kind.CHARGER_FAILURE, "CHG-1", PlannerFixtures.START);
    DriftEvent second =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE, "CHG-1", PlannerFixtures.START, OptionalLong.of(1L));

    FleetPlan repaired =
        ReplanBatch.apply(solver, fleet, current, List.of(first, second), config, clock).plan();

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-1")).isEqualTo("CAB-A");
  }

  @Test
  void aDeadlineBetweenEventsLeavesTheLaterRemovalUnapplied() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-stop", List.of(new ReplanScenes.Hold("CAB-A", trip, 50_000L)));
    Instant at = PlannerFixtures.START;
    AtomicBoolean open = new AtomicBoolean(true);
    ReplanSolver gated =
        (input, plan, event, cfg, clk) -> {
          SolveResult result = solver.replan(input, plan, event, cfg, clk);
          open.set(false);
          return result;
        };

    SolveResult result =
        ReplanBatch.apply(
            gated,
            fleet,
            current,
            List.of(
                new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", at),
                new DriftEvent(DriftEvent.Kind.CAB_REMOVED, "CAB-A", at.plusSeconds(1))),
            config,
            new GateClock(open, at, config.deadline()));

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(result.plan().findVehicleSchedule(VehicleId.of("CAB-A"))).isPresent();
  }

  @Test
  void anEmptyOrNullBatchIsRejected() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-empty", List.of(new ReplanScenes.Hold("CAB-A", trip, 50_000L)));
    DriftEvent event = new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START);
    List<DriftEvent> events = new ArrayList<>();
    events.add(null);

    assertThatThrownBy(() -> ReplanBatch.apply(solver, fleet, current, List.of(), config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("events must not be empty");
    assertThatThrownBy(() -> ReplanBatch.apply(null, fleet, current, List.of(event), config, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ReplanBatch.apply(solver, fleet, current, events, config, clock))
        .isInstanceOf(NullPointerException.class);
  }

  private static FleetPlan planOn(PlannerInput published, CabShift cab, Trip trip) {
    TripAssignment assignment =
        new TripAssignment(
            trip, cab.vehicle().id(), trip.startTs(), trip.plannedDropoffTs(), 0, 0, 0L, 1L, 1L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            cab.vehicle(),
            cab.startLocation(),
            cab.initialSocWh(),
            cab.availableFrom(),
            List.of(assignment));
    return new FleetPlan(
        "plan-on",
        published.tenantId(),
        published.horizonStart(),
        published.horizonEnd(),
        List.of(schedule),
        List.of(),
        1L,
        published.inputHash());
  }

  private static final class GateClock extends Clock {
    private final AtomicBoolean open;
    private final Instant first;
    private final Instant rest;

    private GateClock(AtomicBoolean open, Instant first, Instant rest) {
      this.open = open;
      this.first = first;
      this.rest = rest;
    }

    @Override
    public Instant instant() {
      return open.get() ? first : rest;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
