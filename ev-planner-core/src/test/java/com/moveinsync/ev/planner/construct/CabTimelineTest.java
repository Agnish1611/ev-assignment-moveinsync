package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CabTimelineTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void aChargedTripShowsTheSessionThenTheEmptyDriveThenTheTrip() {
    PlannerInput fleet = chargeFleet();
    SolveResult result = new GreedyConstructionSolver().solve(fleet, config, early);
    VehicleSchedule schedule = busy(result.plan().vehicleSchedules());
    List<TimelineStep> steps = CabTimeline.of(fleet, schedule);

    assertThat(steps)
        .extracting(TimelineStep::kind)
        .containsExactly(
            TimelineStep.Kind.CHARGE, TimelineStep.Kind.DEADHEAD, TimelineStep.Kind.TRIP);
    assertThat(steps.get(0).socAtStartWh()).isEqualTo(schedule.initialSocWh());
    assertThat(steps.get(0).socAtEndWh()).isGreaterThan(steps.get(0).socAtStartWh());
    assertThat(steps.get(1).socAtStartWh()).isEqualTo(steps.get(0).socAtEndWh());
    assertThat(steps.get(1).distanceMeters()).isEqualTo(5_000);
    assertThat(Duration.between(steps.get(1).start(), steps.get(1).end()))
        .isEqualTo(Duration.ofSeconds(500));
    assertThat(steps.get(2).socAtStartWh()).isEqualTo(steps.get(1).socAtEndWh());
    assertThat(steps.get(2).subjectId()).isEqualTo("TRIP-C");
  }

  @Test
  void anIdleCabAndATripThatStartsHereHaveNoEmptyDrive() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();
    VehicleSchedule idle =
        new VehicleSchedule(
            fleet.cabs().get(0).vehicle(),
            fleet.cabs().get(0).startLocation(),
            50_000L,
            PlannerFixtures.START,
            List.of());
    Location pickup = Location.of("LOC-P", "Pickup");
    Trip trip =
        PlannerFixtures.trip(
            "TRIP-H", PlannerFixtures.START.plusSeconds(600), pickup, Location.of("LOC-D", "Drop"));
    TripAssignment assignment =
        new TripAssignment(
            trip,
            VehicleId.of("CAB-1"),
            trip.startTs(),
            trip.plannedDropoffTs(),
            0,
            0,
            0L,
            40_000L,
            38_500L);
    VehicleSchedule direct =
        new VehicleSchedule(
            PlannerFixtures.cab("CAB-1", 40_000L, 150L, pickup).vehicle(),
            pickup,
            40_000L,
            PlannerFixtures.START,
            List.of(assignment));

    assertThat(CabTimeline.of(fleet, idle)).isEmpty();
    assertThat(CabTimeline.of(fleet, direct))
        .extracting(TimelineStep::kind)
        .containsExactly(TimelineStep.Kind.TRIP);
    assertThat(CabTimeline.of(fleet, direct).get(0).socAtStartWh()).isEqualTo(40_000L);
  }

  @Test
  void aLateCabLeavesWhenItIsFree() {
    Location pickup = Location.of("LOC-P", "Pickup");
    Trip trip = PlannerFixtures.trip("TRIP-L", PlannerFixtures.START.plusSeconds(10));
    TripAssignment assignment =
        new TripAssignment(
            trip,
            VehicleId.of("CAB-1"),
            trip.startTs(),
            trip.plannedDropoffTs(),
            5_000,
            0,
            750L,
            49_250L,
            47_750L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            PlannerFixtures.cab("CAB-1", 50_000L).vehicle(),
            PlannerFixtures.depot(),
            50_000L,
            PlannerFixtures.START,
            List.of(assignment));
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();

    TimelineStep deadhead = CabTimeline.of(fleet, schedule).get(0);

    assertThat(deadhead.kind()).isEqualTo(TimelineStep.Kind.DEADHEAD);
    assertThat(deadhead.start()).isEqualTo(PlannerFixtures.START);
    assertThat(deadhead.subjectId()).isEqualTo(pickup.id().value());
    assertThat(deadhead.socAtEndWh()).isEqualTo(49_250L);
  }

  @Test
  void aMissingHubOrRouteIsRejected() {
    Trip trip = PlannerFixtures.trip("TRIP-M", PlannerFixtures.START.plusSeconds(3_600));
    ChargingSession session =
        new ChargingSession(
            "SES-1",
            VehicleId.of("CAB-1"),
            HubId.of("HUB-MISSING"),
            ChargerId.of("CHG-1"),
            PlannerFixtures.START.plusSeconds(60),
            PlannerFixtures.START.plusSeconds(120),
            12_000L,
            20_000L,
            0L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            PlannerFixtures.cab("CAB-1", 12_000L).vehicle(),
            PlannerFixtures.depot(),
            12_000L,
            PlannerFixtures.START,
            List.of(session));
    PlannerInput fleet = PlannerFixtures.shell().build();
    PlannerInput blocked =
        PlannerInput.builder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .addHub(fleet.hubs().get(0))
            .addCharger(fleet.chargers().get(0))
            .distanceProvider(new MatrixDistanceProvider(Map.of()))
            .consumptionModel(fleet.consumptionModel())
            .build();
    VehicleSchedule driving =
        new VehicleSchedule(
            PlannerFixtures.cab("CAB-1", 50_000L).vehicle(),
            PlannerFixtures.depot(),
            50_000L,
            PlannerFixtures.START,
            List.of(
                new TripAssignment(
                    trip,
                    VehicleId.of("CAB-1"),
                    trip.startTs(),
                    trip.plannedDropoffTs(),
                    5_000,
                    0,
                    750L,
                    49_250L,
                    47_750L)));

    assertThatThrownBy(() -> CabTimeline.of(fleet, schedule))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CabTimeline.of(blocked, driving))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CabTimeline.of(null, schedule))
        .isInstanceOf(NullPointerException.class);
  }

  private static PlannerInput chargeFleet() {
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-C",
                PlannerFixtures.START.plusSeconds(3_600),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static VehicleSchedule busy(List<VehicleSchedule> schedules) {
    for (VehicleSchedule schedule : schedules) {
      if (!schedule.isEmpty()) {
        return schedule;
      }
    }
    throw new AssertionError("missing schedule");
  }
}
