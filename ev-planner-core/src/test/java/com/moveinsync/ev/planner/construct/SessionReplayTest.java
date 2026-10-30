package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SessionReplayTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);
  private final SplittableRandom random = new SplittableRandom(1L);

  @Test
  void sessionsThatDoNotMatchTheCabAreRejected() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();
    reject(fleet, session("CAB-X", "HUB-1", "CHG-1", 10, 20, 50_000L, 51_000L));
    reject(fleet, session("CAB-1", "HUB-X", "CHG-1", 10, 20, 50_000L, 51_000L));
    reject(fleet, session("CAB-1", "HUB-1", "CHG-X", 10, 20, 50_000L, 51_000L));
    reject(fleet, session("CAB-1", "HUB-1", "CHG-1", -5, 10, 50_000L, 51_000L));
    reject(fleet, session("CAB-1", "HUB-1", "CHG-1", 10, 20, 50_000L, 40_000L));
    reject(fleet, session("CAB-1", "HUB-1", "CHG-1", 10, 20, 50_000L, 90_000L));
    reject(fleet, session("CAB-1", "HUB-1", "CHG-1", 10, 10, 50_000L, 50_000L));
  }

  @Test
  void aFaultyChargerRejectsAMatchingSession() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    PlannerInput fleet =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(hub)
            .addCharger(
                new Charger(
                    PlannerFixtures.TENANT,
                    ChargerId.of("CHG-1"),
                    hub.id(),
                    50,
                    ChargerStatus.FAULTY,
                    List.of()))
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    reject(fleet, session("CAB-1", "HUB-1", "CHG-1", 10, 40, 50_000L, 51_000L));
  }

  @Test
  void aSecondCabCannotReuseABusyPlug() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addCab(PlannerFixtures.cab("CAB-2", 50_000L))
            .build();
    CabShift first = fleet.cabs().get(0);
    CabShift second = fleet.cabs().get(1);
    FleetPlan plan =
        new FleetPlan(
            "hand",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(
                schedule(first, session("CAB-1", "HUB-1", "CHG-1", 10, 100, 50_000L, 51_000L)),
                schedule(second, session("CAB-2", "HUB-1", "CHG-1", 40, 120, 50_000L, 51_000L))),
            List.of(),
            1L,
            fleet.inputHash());

    assertThatThrownBy(() -> new RandomTripRemoval().destroy(fleet, plan, config, random, early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("charging");
  }

  private void reject(PlannerInput fleet, ChargingSession session) {
    assertThatThrownBy(
            () ->
                new RandomTripRemoval()
                    .destroy(
                        fleet,
                        PlanViews.withActivities(fleet, List.of(session)),
                        config,
                        random,
                        early))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("charging");
  }

  private static VehicleSchedule schedule(CabShift cab, ChargingSession session) {
    return new VehicleSchedule(
        cab.vehicle(),
        cab.startLocation(),
        cab.initialSocWh(),
        cab.availableFrom(),
        List.of(session));
  }

  private static ChargingSession session(
      String cab,
      String hub,
      String charger,
      long startOffset,
      long endOffset,
      long startSoc,
      long target) {
    return new ChargingSession(
        "SES",
        VehicleId.of(cab),
        HubId.of(hub),
        ChargerId.of(charger),
        PlannerFixtures.START.plusSeconds(startOffset),
        PlannerFixtures.START.plusSeconds(endOffset),
        startSoc,
        target,
        0L);
  }
}
