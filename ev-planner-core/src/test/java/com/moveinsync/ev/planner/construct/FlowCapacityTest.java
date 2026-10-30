package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowCapacityTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void twoPlugsUnderHeavyDemandDoNotShareACharger() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            2);
    PlannerInput fleet =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(hub)
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-2", "HUB-1", 50))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addCab(PlannerFixtures.cab("CAB-2", 12_000L))
            .addCab(PlannerFixtures.cab("CAB-3", 12_000L))
            .addCab(PlannerFixtures.cab("CAB-4", 12_000L))
            .addTrip(tight("TRIP-A"))
            .addTrip(tight("TRIP-B"))
            .addTrip(tight("TRIP-C"))
            .addTrip(tight("TRIP-D"))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    FleetPlan plan = solver.solve(fleet, config, early).plan();
    List<ChargingSession> sessions = sessions(plan);

    assertThat(plan.totalAssignedTrips()).isEqualTo(2);
    assertThat(sessions).hasSize(2);
    assertThat(congestion(plan)).isEqualTo(2);
    assertThat(sessions.get(0).chargerId()).isNotEqualTo(sessions.get(1).chargerId());
    assertThat(sessions.get(0).overlaps(sessions.get(1))).isTrue();
  }

  private static Trip tight(String id) {
    return PlannerFixtures.trip(
        id,
        PlannerFixtures.START.plusSeconds(700),
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000);
  }

  private static List<ChargingSession> sessions(FleetPlan plan) {
    return plan.vehicleSchedules().stream()
        .flatMap(schedule -> schedule.activities().stream())
        .filter(ChargingSession.class::isInstance)
        .map(ChargingSession.class::cast)
        .toList();
  }

  private static int congestion(FleetPlan plan) {
    int count = 0;
    for (var unassigned : plan.unassignedTrips()) {
      if (unassigned.reasonCode() == UnassignedReasonCode.CHARGER_CONGESTION) {
        count++;
      }
    }
    return count;
  }
}
