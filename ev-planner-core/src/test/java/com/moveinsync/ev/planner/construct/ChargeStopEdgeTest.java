package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChargeStopEdgeTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void tariffWindowsArePricedOnlyWhereTheyOverlapTheSession() {
    HubId hubId = HubId.of("HUB-1");
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addHub(
                Hub.of(
                    PlannerFixtures.TENANT.value(),
                    "HUB-2",
                    "Other",
                    PlannerFixtures.depot(),
                    ZoneId.of("UTC"),
                    1))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addTrip(lateTrip("TRIP-C"))
            .addTariff(
                new TariffSchedule(
                    hubId,
                    List.of(
                        new UtcTariffWindow(
                            hubId,
                            PlannerFixtures.START.minusSeconds(500),
                            PlannerFixtures.START.minusSeconds(10),
                            50_000L),
                        new UtcTariffWindow(
                            hubId,
                            PlannerFixtures.START.plusSeconds(20),
                            PlannerFixtures.START.plusSeconds(40),
                            80_000L),
                        new UtcTariffWindow(
                            hubId, PlannerFixtures.START, PlannerFixtures.END, 100_000L))))
            .addTariff(
                new TariffSchedule(
                    HubId.of("HUB-2"),
                    List.of(
                        new UtcTariffWindow(
                            HubId.of("HUB-2"), PlannerFixtures.START, PlannerFixtures.END, 10L))))
            .build();

    var plan = solver.solve(fleet, config, early).plan();

    assertThat(plan.totalChargingSessions()).isEqualTo(1);
    assertThat(plan.totalChargingCostMicros()).isPositive();
  }

  @Test
  void aFaultyPlugLeavesTheTripUnassigned() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    Charger charger =
        new Charger(
            PlannerFixtures.TENANT,
            ChargerId.of("CHG-1"),
            hub.id(),
            50,
            ChargerStatus.FAULTY,
            List.of());
    PlannerInput fleet =
        fleet(hub, List.of(charger))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addTrip(lateTrip("TRIP-C"))
            .build();

    var plan = solver.solve(fleet, config, early).plan();

    assertThat(plan.totalAssignedTrips()).isZero();
    assertThat(plan.unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE);
  }

  @Test
  void twoChargersAtAOnePlugHubDoNotOverlap() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    PlannerInput fleet =
        fleet(
                hub,
                List.of(
                    Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50),
                    Charger.of(PlannerFixtures.TENANT.value(), "CHG-2", "HUB-1", 50)))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addCab(PlannerFixtures.cab("CAB-2", 12_000L))
            .addTrip(lateTrip("TRIP-A"))
            .addTrip(lateTrip("TRIP-B"))
            .build();

    var plan = solver.solve(fleet, config, early).plan();
    List<ChargingSession> sessions = sessions(plan);

    assertThat(plan.totalAssignedTrips()).isEqualTo(2);
    assertThat(sessions).hasSize(2);
    assertThat(sessions.get(0).overlaps(sessions.get(1))).isFalse();
  }

  @Test
  void aDropoffWithNoRoadToAHubIsIntrinsic() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .distanceProvider(new MatrixDistanceProvider(Map.of()))
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-R",
                    PlannerFixtures.START.plusSeconds(3_600),
                    PlannerFixtures.depot(),
                    Location.of("LOC-D", "Drop"),
                    10_000))
            .build();

    assertThat(solver.solve(fleet, config, early).plan().unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
  }

  @Test
  void aHubWithNoRoadToThePickupCannotCharge() {
    Location pickup = Location.of("LOC-P", "Pickup");
    PlannerInput fleet =
        PlannerFixtures.shell()
            .distanceProvider(new MatrixDistanceProvider(Map.of()))
            .addCab(PlannerFixtures.cab("CAB-1", 10_900L, 150L, pickup))
            .addTrip(
                PlannerFixtures.trip(
                    "TRIP-E",
                    PlannerFixtures.START.plusSeconds(3_600),
                    pickup,
                    Location.of("LOC-D", "Drop"),
                    10_000))
            .build();

    assertThat(solver.solve(fleet, config, early).plan().unassignedTrips().get(0).reasonCode())
        .isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
  }

  private static PlannerInput.Builder fleet(Hub hub, List<Charger> chargers) {
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(hub)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    chargers.forEach(builder::addCharger);
    return builder;
  }

  private static Trip lateTrip(String id) {
    return PlannerFixtures.trip(
        id,
        PlannerFixtures.START.plusSeconds(3_600),
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000);
  }

  private static List<ChargingSession> sessions(FleetPlan plan) {
    List<ChargingSession> found = new ArrayList<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (var activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          found.add(session);
        }
      }
    }
    return found;
  }
}
