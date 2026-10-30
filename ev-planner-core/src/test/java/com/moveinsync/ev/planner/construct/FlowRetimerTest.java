package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.FlowBound;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowRetimerTest {

  private final GreedyConstructionSolver solver = new GreedyConstructionSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aLaterCheapWindowPullsTheSessionOffTheExpensiveTariff() {
    PlannerInput fleet = cheapAfter(PlannerFixtures.START.plusSeconds(400));

    ChargingSession session = onlySession(solver.solve(fleet, config, early).plan());

    assertThat(session.startTime()).isAfterOrEqualTo(PlannerFixtures.START.plusSeconds(400));
    assertThat(session.costMicros()).isPositive();
    assertThat(session.hubId()).isEqualTo(HubId.of("HUB-1"));
  }

  @Test
  void splitCheapFragmentsStayOneBlockAndLeaveAGap() {
    PlannerInput fleet =
        fleet(1, List.of(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50)))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addTrip(trip("TRIP-C"))
            .addTariff(
                new TariffSchedule(
                    HubId.of("HUB-1"),
                    List.of(
                        window(0, 80, 1_000L),
                        window(80, 200, 100_000L),
                        window(200, 280, 1_000L),
                        window(280, 28_800, 100_000L))))
            .build();

    SolveResult result = solver.solve(fleet, config, early);
    ChargingSession session = onlySession(result.plan());

    assertThat(session.startTime()).isEqualTo(PlannerFixtures.START);
    assertThat(session.endTime()).isAfter(PlannerFixtures.START.plusSeconds(80));
    assertThat(result.flow().relaxationMicros()).isLessThan(result.flow().achievedMicros());
    assertThat(result.flow().gapMicros()).isPositive();
  }

  @Test
  void onePlugSerialisesTwoSessionsInsideTheCheapWindow() {
    PlannerInput fleet =
        fleet(1, List.of(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50)))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addCab(PlannerFixtures.cab("CAB-2", 12_000L))
            .addTrip(trip("TRIP-A"))
            .addTrip(trip("TRIP-B"))
            .addTariff(
                new TariffSchedule(
                    HubId.of("HUB-1"),
                    List.of(window(0, 1_000, 200_000L), window(1_000, 28_800, 1_000L))))
            .build();

    FleetPlan plan = solver.solve(fleet, config, early).plan();
    List<ChargingSession> sessions = sessions(plan);

    assertThat(plan.totalAssignedTrips()).isEqualTo(2);
    assertThat(sessions).hasSize(2);
    assertThat(sessions.get(0).overlaps(sessions.get(1))).isFalse();
    assertThat(sessions.get(0).startTime())
        .isAfterOrEqualTo(PlannerFixtures.START.plusSeconds(1_000));
    assertThat(sessions.get(1).startTime())
        .isAfterOrEqualTo(PlannerFixtures.START.plusSeconds(1_000));
  }

  @Test
  void aCheaperSecondHubTakesTheSession() {
    Hub expensive =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    Hub cheap =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-2",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    PlannerInput fleet =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(expensive)
            .addHub(cheap)
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-2", "HUB-2", 50))
            .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
            .addTrip(trip("TRIP-C"))
            .addTariff(new TariffSchedule(HubId.of("HUB-1"), List.of(window(0, 28_800, 100_000L))))
            .addTariff(new TariffSchedule(HubId.of("HUB-2"), List.of(cheapWindow())))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    ChargingSession session = onlySession(solver.solve(fleet, config, early).plan());

    assertThat(session.hubId()).isEqualTo(HubId.of("HUB-2"));
    assertThat(session.costMicros()).isPositive().isLessThan(20_000L);
  }

  @Test
  void aDeadlineAfterConstructionLeavesTheExpensiveSession() {
    PlannerInput fleet = cheapAfter(PlannerFixtures.START.plusSeconds(400));

    ChargingSession session = onlySession(solver.solve(fleet, config, new EarlyThenLate()).plan());

    assertThat(session.startTime()).isEqualTo(PlannerFixtures.START);
  }

  @Test
  void nullArgumentsAreRejected() {
    PlannerInput fleet = cheapAfter(PlannerFixtures.START.plusSeconds(400));
    FleetPlan plan = solver.solve(fleet, config, early).plan();

    assertThatThrownBy(() -> FlowRetimer.retime(null, plan, config, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> FlowRetimer.retime(fleet, null, config, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> FlowRetimer.retime(fleet, plan, null, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> FlowRetimer.retime(fleet, plan, config, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FlowBound(-1L, 0L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowBound(0L, -1L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowBound(2L, 1L)).isInstanceOf(IllegalArgumentException.class);
  }

  private static PlannerInput cheapAfter(Instant cheapStart) {
    long cheapOffset = cheapStart.getEpochSecond() - PlannerFixtures.START.getEpochSecond();
    return fleet(1, List.of(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50)))
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addTrip(trip("TRIP-C"))
        .addTariff(
            new TariffSchedule(
                HubId.of("HUB-1"),
                List.of(window(0, cheapOffset, 200_000L), window(cheapOffset, 28_800, 1_000L))))
        .build();
  }

  private static PlannerInput.Builder fleet(int chargerCount, List<Charger> chargers) {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            chargerCount);
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

  private static Trip trip(String id) {
    return PlannerFixtures.trip(
        id,
        PlannerFixtures.START.plusSeconds(3_600),
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000);
  }

  private static UtcTariffWindow cheapWindow() {
    return new UtcTariffWindow(
        HubId.of("HUB-2"), PlannerFixtures.START, PlannerFixtures.END, 1_000L);
  }

  private static UtcTariffWindow window(long startOffset, long endOffset, long rate) {
    return new UtcTariffWindow(
        HubId.of("HUB-1"),
        PlannerFixtures.START.plusSeconds(startOffset),
        PlannerFixtures.START.plusSeconds(endOffset),
        rate);
  }

  private static ChargingSession onlySession(FleetPlan plan) {
    List<ChargingSession> found = sessions(plan);
    if (found.size() != 1) {
      throw new AssertionError("expected one session, found " + found.size());
    }
    return found.get(0);
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

  private static final class EarlyThenLate extends Clock {
    private int reads;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      int seen = reads;
      reads++;
      return seen < 2 ? PlannerFixtures.START : PlannerFixtures.END;
    }
  }
}
