package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TripRemovalFailureTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock open =
      Clock.fixed(PlannerFixtures.START.minusSeconds(10_000), ZoneOffset.UTC);
  private final SplittableRandom random = new SplittableRandom(1L);

  @Test
  void chargingSessionsAreRejected() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();
    ChargingSession session =
        new ChargingSession(
            "SES-1",
            VehicleId.of("CAB-1"),
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            PlannerFixtures.START.plusSeconds(10),
            PlannerFixtures.START.plusSeconds(20),
            1_000L,
            2_000L,
            0L);
    FleetPlan plan = PlanViews.withActivities(fleet, List.of(session));

    assertThatThrownBy(() -> new RandomTripRemoval().destroy(fleet, plan, config, random, open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("charging");
  }

  @Test
  void aDifferentTenantIsRejected() {
    PlannerInput fleet = PlannerFixtures.sample();
    FleetPlan other =
        new FleetPlan(
            "other",
            TenantId.of("tenant-b"),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(),
            List.of(),
            1L,
            fleet.inputHash());

    assertThatThrownBy(() -> new ShawRemoval().destroy(fleet, other, config, random, open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");
  }

  @Test
  void nullArgumentsAreRejected() {
    FleetPlan plan =
        new GreedyConstructionSolver()
            .solve(
                PlannerFixtures.sample(),
                config,
                Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC))
            .plan();

    assertThatThrownBy(() -> new RandomTripRemoval().destroy(null, plan, config, random, open))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anUnknownVehicleIsRejected() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();
    Vehicle foreign = new Vehicle(PlannerFixtures.TENANT, VehicleId.of("CAB-X"), 60_000L, 150L, 50);
    VehicleSchedule schedule =
        new VehicleSchedule(
            foreign, PlannerFixtures.depot(), 50_000L, PlannerFixtures.START, List.of());
    FleetPlan plan =
        new FleetPlan(
            "hand",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(schedule),
            List.of(),
            1L,
            fleet.inputHash());

    assertThatThrownBy(() -> new WorstCostRemoval().destroy(fleet, plan, config, random, open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not in the planner input");
  }

  @Test
  void aDuplicateScheduleIsRejected() {
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).build();
    CabShift cab = fleet.cabs().get(0);
    VehicleSchedule schedule =
        new VehicleSchedule(
            cab.vehicle(), cab.startLocation(), cab.initialSocWh(), cab.availableFrom(), List.of());
    FleetPlan plan =
        new FleetPlan(
            "hand",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(schedule, schedule),
            List.of(),
            1L,
            fleet.inputHash());

    assertThatThrownBy(() -> new RandomTripRemoval().destroy(fleet, plan, config, random, open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate vehicle");
  }

  @Test
  void aTripAssignedTwiceIsRejected() {
    Trip trip = PlannerFixtures.trip("TRIP-1", PlannerFixtures.START.plusSeconds(600));
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addCab(PlannerFixtures.cab("CAB-2", 50_000L))
            .addTrip(trip)
            .build();
    VehicleSchedule first = schedule(fleet.cabs().get(0), trip, "CAB-1");
    VehicleSchedule second = schedule(fleet.cabs().get(1), trip, "CAB-2");
    FleetPlan plan =
        new FleetPlan(
            "hand",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(first, second),
            List.of(),
            1L,
            fleet.inputHash());

    assertThatThrownBy(() -> new ShawRemoval().destroy(fleet, plan, config, random, open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("more than once");
  }

  private static VehicleSchedule schedule(CabShift cab, Trip trip, String cabId) {
    return new VehicleSchedule(
        cab.vehicle(),
        cab.startLocation(),
        cab.initialSocWh(),
        cab.availableFrom(),
        List.of(
            new com.moveinsync.ev.domain.schedule.TripAssignment(
                trip,
                VehicleId.of(cabId),
                trip.startTs(),
                trip.plannedDropoffTs(),
                0,
                0,
                0L,
                1L,
                1L)));
  }
}
