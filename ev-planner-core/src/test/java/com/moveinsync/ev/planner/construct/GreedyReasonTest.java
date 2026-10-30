package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GreedyReasonTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aTripThatStartsTooSoonIsATimeMiss() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(PlannerFixtures.trip("TRIP-T", PlannerFixtures.START.plusSeconds(10)))
            .build();

    assertThat(reason(fleet)).isEqualTo(UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW);
  }

  @Test
  void aTripBelowThePlanningFloorIsAnEnergyMiss() {
    Location pickup = Location.of("LOC-P", "Pickup");
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 10_900L, 150L, pickup))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-E",
                    PlannerFixtures.START.plusSeconds(600),
                    pickup,
                    Location.of("LOC-D", "Drop"),
                    10_000))
            .build();

    assertThat(reason(fleet)).isEqualTo(UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE);
  }

  @Test
  void aTripThatCannotReachAHubIsAReserveMiss() {
    Location pickup = Location.of("LOC-P", "Pickup");
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 12_500L, 150L, pickup))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-H",
                    PlannerFixtures.START.plusSeconds(600),
                    pickup,
                    Location.of("LOC-D", "Drop"),
                    10_000))
            .build();

    assertThat(reason(fleet)).isEqualTo(UnassignedReasonCode.RESERVE_TO_HUB_VIOLATION);
  }

  @Test
  void aTripThatNoFullBatteryCanFinishIsIntrinsic() {
    PlannerInput tooLong =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-I",
                    PlannerFixtures.START.plusSeconds(600),
                    Location.of("LOC-P", "Pickup"),
                    Location.of("LOC-D", "Drop"),
                    1_000_000))
            .build();
    PlannerInput tooTight =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-J",
                    PlannerFixtures.START.plusSeconds(600),
                    PlannerFixtures.depot(),
                    Location.of("LOC-D", "Drop"),
                    350_000))
            .build();

    assertThat(reason(tooLong)).isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
    assertThat(reason(tooTight)).isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
  }

  @Test
  void aMissingPickupRouteIsReportedWithoutACabIdInTheExplanation() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .distanceProvider(new MatrixDistanceProvider(Map.of()))
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-R",
                    PlannerFixtures.START.plusSeconds(600),
                    Location.of("LOC-P", "Pickup"),
                    PlannerFixtures.depot(),
                    10_000))
            .build();

    var unassigned = solver.solve(fleet, config, early).plan().unassignedTrips().get(0);

    assertThat(unassigned.reasonCode()).isEqualTo(UnassignedReasonCode.OTHER);
    assertThat(unassigned.explanation()).doesNotContain("@");
  }

  @Test
  void noCabsMakesEveryTripIntrinsic() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addTrip(PlannerFixtures.trip("TRIP-1", PlannerFixtures.START.plusSeconds(600)))
            .build();

    assertThat(reason(fleet)).isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
  }

  private UnassignedReasonCode reason(PlannerInput fleet) {
    var plan = solver.solve(fleet, config, early).plan();
    assertThat(plan.totalAssignedTrips()).isZero();
    assertThat(plan.unassignedTrips()).hasSize(1);
    return plan.unassignedTrips().get(0).reasonCode();
  }
}
