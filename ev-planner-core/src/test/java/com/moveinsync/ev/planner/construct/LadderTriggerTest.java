package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Trip edits, cab removal, and a cab already below the floor. */
class LadderTriggerTest {

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);
  private final Clock clock = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void anAddedTripThatFitsIsAssigned() {
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 3_600, 2_000, 1_200);
    Trip added = ReplanScenes.trip("TRIP-NEW", 7_200, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept, added));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-add", List.of(hold("CAB-A", kept)));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.TRIP_ADDED, "TRIP-NEW");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(ReplanScenes.cabOf(repaired, "TRIP-NEW")).isEqualTo("CAB-A");
  }

  @Test
  void anAddedTripThatCannotMakePickupGoesToBackup() {
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 3_600, 2_000, 1_200);
    Trip added = ReplanScenes.trip("TRIP-NEW", 10, 10_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept, added));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-soon", List.of(hold("CAB-A", kept)));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.TRIP_ADDED, "TRIP-NEW");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(ReplanScenes.cabOf(repaired, "TRIP-NEW")).isEmpty();
    assertThat(repaired.unassignedTrips())
        .anyMatch(row -> row.trip().id().value().equals("TRIP-NEW"));
  }

  @Test
  void anAlreadyAssignedAddIsLeftAlone() {
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-same", List.of(hold("CAB-A", kept)));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.TRIP_ADDED, "TRIP-KEEP");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(repaired.totalUnassignedTrips()).isZero();
  }

  @Test
  void aParkedTripIsReopenedWhenItIsAddedAgain() {
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 3_600, 2_000, 1_200);
    Trip parked = ReplanScenes.trip("TRIP-PARK", 7_200, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept, parked));
    FleetPlan current =
        ReplanScenes.plan(
            fleet,
            "plan-park",
            List.of(hold("CAB-A", kept)),
            List.of(UnassignedTrip.of(parked, UnassignedReasonCode.OTHER, "earlier")));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.TRIP_ADDED, "TRIP-PARK");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-PARK")).isEqualTo("CAB-A");
  }

  @Test
  void aCancelledTripLeavesThePlan() {
    Trip gone = ReplanScenes.trip("TRIP-GONE", 3_600, 2_000, 1_200);
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 7_200, 2_000, 1_200);
    PlannerInput full =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(gone, kept));
    PlannerInput reduced =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept));
    FleetPlan current = ReplanScenes.plan(full, "plan-cancel", List.of(hold("CAB-A", gone)));

    FleetPlan repaired = replan(reduced, current, DriftEvent.Kind.TRIP_CANCELLED, "TRIP-GONE");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(mentions(repaired, "TRIP-GONE")).isFalse();
  }

  @Test
  void aCancelledBackupTripIsDropped() {
    Trip gone = ReplanScenes.trip("TRIP-GONE", 3_600, 2_000, 1_200);
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 7_200, 2_000, 1_200);
    PlannerInput full =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(gone, kept));
    PlannerInput reduced =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept));
    FleetPlan current =
        ReplanScenes.plan(
            full,
            "plan-parked-cancel",
            List.of(hold("CAB-A", kept)),
            List.of(UnassignedTrip.of(gone, UnassignedReasonCode.OTHER, "earlier")));

    FleetPlan repaired = replan(reduced, current, DriftEvent.Kind.TRIP_CANCELLED, "TRIP-GONE");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-KEEP")).isEqualTo("CAB-A");
    assertThat(mentions(repaired, "TRIP-GONE")).isFalse();
  }

  @Test
  void aCancelledTripThatIsStillInTheInputIsRejected() {
    Trip kept = ReplanScenes.trip("TRIP-KEEP", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(kept));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-still", List.of(hold("CAB-A", kept)));

    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.TRIP_CANCELLED, "TRIP-KEEP"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("cancelled trip is still in the planner input");
  }

  @Test
  void aRemovedCabLosesItsTripToTheRemainingCab() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    CabShift removed = PlannerFixtures.cab("CAB-A", 50_000L);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-B", 50_000L)), List.of(trip));
    FleetPlan current = planOn(fleet, removed, trip);

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.CAB_REMOVED, "CAB-A");

    assertThat(ReplanScenes.cabOf(repaired, "TRIP-1")).isEqualTo("CAB-B");
    assertThat(repaired.findVehicleSchedule(VehicleId.of("CAB-A"))).isEmpty();
  }

  @Test
  void aRemovedCabThatIsStillInTheInputIsRejected() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 50_000L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(trip));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-present", List.of(hold("CAB-A", trip)));

    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.CAB_REMOVED, "CAB-A"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("removed cab is still in the planner input");
  }

  @Test
  void aCabBelowTheFloorIsOmittedAndItsTripMoves() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 8_000L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(trip));
    FleetPlan current =
        ReplanScenes.plan(fleet, "plan-floor", List.of(hold("CAB-A", trip, 8_000L)));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.BATTERY, "CAB-A");

    assertThat(repaired.findVehicleSchedule(VehicleId.of("CAB-A"))).isEmpty();
    assertThat(ReplanScenes.cabOf(repaired, "TRIP-1")).isEqualTo("CAB-B");
  }

  @Test
  void aCabExactlyAtTheFloorStaysOnThePlan() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(
            List.of(PlannerFixtures.cab("CAB-A", 9_000L), PlannerFixtures.cab("CAB-B", 50_000L)),
            List.of(trip));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-edge", List.of(hold("CAB-A", trip, 9_000L)));

    FleetPlan repaired = replan(fleet, current, DriftEvent.Kind.BATTERY, "CAB-A");

    assertThat(repaired.findVehicleSchedule(VehicleId.of("CAB-A"))).isPresent();
  }

  @Test
  void unknownSubjectsAreRejected() {
    Trip trip = ReplanScenes.trip("TRIP-1", 3_600, 2_000, 1_200);
    PlannerInput fleet =
        ReplanScenes.fleet(List.of(PlannerFixtures.cab("CAB-A", 50_000L)), List.of(trip));
    FleetPlan current = ReplanScenes.plan(fleet, "plan-unknown", List.of(hold("CAB-A", trip)));

    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.TRIP_ADDED, "TRIP-Z"))
        .hasMessageContaining("not a trip");
    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.TRIP_CANCELLED, "TRIP-Z"))
        .hasMessageContaining("not a trip");
    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.CAB_REMOVED, "CAB-Z"))
        .hasMessageContaining("not a cab");
    assertThatThrownBy(() -> replan(fleet, current, DriftEvent.Kind.CHARGER_FAILURE, "CHG-Z"))
        .hasMessageContaining("not a charger");
    assertThatThrownBy(
            () ->
                new DriftEvent(
                    DriftEvent.Kind.BATTERY, "CAB-A", PlannerFixtures.START, OptionalLong.of(-1L)))
        .hasMessageContaining("non-negative");
  }

  private FleetPlan replan(
      PlannerInput fleet, FleetPlan current, DriftEvent.Kind kind, String subject) {
    return solver
        .replan(fleet, current, new DriftEvent(kind, subject, PlannerFixtures.START), config, clock)
        .plan();
  }

  private static ReplanScenes.Hold hold(String cabId, Trip trip) {
    return hold(cabId, trip, 50_000L);
  }

  private static ReplanScenes.Hold hold(String cabId, Trip trip, long socWh) {
    return new ReplanScenes.Hold(cabId, trip, socWh);
  }

  private static boolean mentions(FleetPlan plan, String tripId) {
    if (ReplanScenes.cabOf(plan, tripId).length() > 0) {
      return true;
    }
    for (UnassignedTrip row : plan.unassignedTrips()) {
      if (row.trip().id().value().equals(tripId)) {
        return true;
      }
    }
    return false;
  }

  private static FleetPlan planOn(PlannerInput published, CabShift cab, Trip trip) {
    return new FleetPlan(
        "plan-on",
        published.tenantId(),
        published.horizonStart(),
        published.horizonEnd(),
        List.of(schedule(cab, List.of(assignment(cab, trip)))),
        List.of(),
        1L,
        published.inputHash());
  }

  private static VehicleSchedule schedule(CabShift cab, List<RouteActivity> activities) {
    return new VehicleSchedule(
        cab.vehicle(), cab.startLocation(), cab.initialSocWh(), cab.availableFrom(), activities);
  }

  private static TripAssignment assignment(CabShift cab, Trip trip) {
    return new TripAssignment(
        trip, cab.vehicle().id(), trip.startTs(), trip.plannedDropoffTs(), 0, 0, 0L, 1L, 1L);
  }
}
