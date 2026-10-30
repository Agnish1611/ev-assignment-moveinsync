package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A failed plug is marked down and an active session moves to another plug. */
class ChargerTriggerTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aFailedPlugMovesTheSessionOntoAnotherPlug() {
    PlannerInput fleet = twoPlugs();
    FleetPlan current = new GreedyConstructionSolver().solve(fleet, config, clock).plan();
    ChargingSession session = onlySession(current);
    DriftEvent event =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            session.chargerId().value(),
            session.startTime(),
            OptionalLong.of(12_000L));

    FleetPlan repaired = solver.replan(fleet, current, event, config, clock).plan();

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-C")).isEqualTo("CAB-1");
    assertThat(onlySession(repaired).chargerId()).isNotEqualTo(session.chargerId());
  }

  @Test
  void aReportedSocWithoutASessionIsRejected() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(
            fleet, "plan-soc", List.of(new ReplanScenes.Hold("CAB-A", trip, 50_000L)));
    DriftEvent unread =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START,
            OptionalLong.of(12_000L));

    assertThatThrownBy(() -> solver.replan(fleet, current, unread, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reported SoC has no active session");
  }

  @Test
  void aReadingOutsideTheSessionIsRejected() {
    PlannerInput fleet = twoPlugs();
    FleetPlan current = charged(fleet, "CAB-1", "CHG-1");
    DriftEvent before =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START,
            OptionalLong.of(12_000L));
    DriftEvent atEnd =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START.plusSeconds(610),
            OptionalLong.of(12_000L));
    DriftEvent otherPlug =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-2",
            PlannerFixtures.START.plusSeconds(10),
            OptionalLong.of(12_000L));

    assertThatThrownBy(() -> solver.replan(fleet, current, before, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reported SoC has no active session");
    assertThatThrownBy(() -> solver.replan(fleet, current, atEnd, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reported SoC has no active session");
    assertThatThrownBy(() -> solver.replan(fleet, current, otherPlug, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reported SoC has no active session");
  }

  @Test
  void aReportedSocThatDisagreesWithTheCabIsRejected() {
    PlannerInput fleet = twoPlugs();
    FleetPlan current = charged(fleet, "CAB-1", "CHG-1");
    DriftEvent event =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START.plusSeconds(10),
            OptionalLong.of(13_000L));

    assertThatThrownBy(() -> solver.replan(fleet, current, event, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("reported SoC does not match the cab");
  }

  @Test
  void aReportedSocForAMissingCabIsRejected() {
    PlannerInput fleet = PlannerFixtures.shell().build();
    CabShift ghost = PlannerFixtures.cab("CAB-A", 12_000L);
    FleetPlan current = plan(fleet, ghost, List.of(session("SESS-1", "CAB-A", "CHG-1")));
    DriftEvent event =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START.plusSeconds(10),
            OptionalLong.of(12_000L));

    assertThatThrownBy(() -> solver.replan(fleet, current, event, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("active session cab is not in the planner input");
  }

  @Test
  void twoSessionsOnTheFailedPlugAreRejected() {
    PlannerInput fleet = twoPlugs();
    FleetPlan current = twoSessions(fleet);
    DriftEvent event =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            "CHG-1",
            PlannerFixtures.START.plusSeconds(10),
            OptionalLong.of(12_000L));

    assertThatThrownBy(() -> solver.replan(fleet, current, event, config, clock))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("charger has two active sessions");
  }

  private static FleetPlan charged(PlannerInput fleet, String cabId, String chargerId) {
    return plan(fleet, cab(fleet, cabId), List.of(session("SESS-1", cabId, chargerId)));
  }

  private static FleetPlan twoSessions(PlannerInput fleet) {
    CabShift first = cab(fleet, "CAB-1");
    CabShift second = PlannerFixtures.cab("CAB-2", 12_000L);
    return new FleetPlan(
        "plan-two",
        fleet.tenantId(),
        fleet.horizonStart(),
        fleet.horizonEnd(),
        List.of(
            schedule(first, List.of(session("SESS-1", "CAB-1", "CHG-1"))),
            schedule(second, List.of(session("SESS-2", "CAB-2", "CHG-1")))),
        List.of(),
        1L,
        fleet.inputHash());
  }

  private static FleetPlan plan(PlannerInput fleet, CabShift cab, List<RouteActivity> activities) {
    return new FleetPlan(
        "plan-charge",
        fleet.tenantId(),
        fleet.horizonStart(),
        fleet.horizonEnd(),
        List.of(schedule(cab, activities)),
        List.of(),
        1L,
        fleet.inputHash());
  }

  private static CabShift cab(PlannerInput fleet, String cabId) {
    for (CabShift cab : fleet.cabs()) {
      if (cab.vehicle().id().value().equals(cabId)) {
        return cab;
      }
    }
    throw new IllegalStateException("cab is not in the fleet");
  }

  private static VehicleSchedule schedule(CabShift cab, List<RouteActivity> activities) {
    return new VehicleSchedule(
        cab.vehicle(), cab.startLocation(), cab.initialSocWh(), cab.availableFrom(), activities);
  }

  private static ChargingSession session(String id, String cabId, String chargerId) {
    Instant start = PlannerFixtures.START.plusSeconds(10);
    return new ChargingSession(
        id,
        VehicleId.of(cabId),
        HubId.of("HUB-1"),
        ChargerId.of(chargerId),
        start,
        start.plusSeconds(600),
        12_000L,
        20_000L,
        0L);
  }

  private static PlannerInput twoPlugs() {
    return PlannerFixtures.shell()
        .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-2", "HUB-1", 50))
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addTrip(PlannerFixtures.trip("TRIP-C", PlannerFixtures.START.plusSeconds(3_600)))
        .build();
  }

  private static ChargingSession onlySession(FleetPlan plan) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          return session;
        }
      }
    }
    throw new AssertionError("missing charging session");
  }
}
