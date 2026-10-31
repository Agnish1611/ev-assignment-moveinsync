package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.FleetLowerBound;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.service.fleet.FleetDefaults;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import javax.sql.DataSource;

/** The 41 percent fleet and a two-plug fleet, stored and published as version 1. */
final class DriftFleet {

  static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  static final Instant END = START.plusSeconds(28_800);
  static final String SHARD = "shard-drift";
  static final long DRIFTED = 13_200L;

  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");
  private static final Location PICKUP = Location.of("LOC-P", "Pickup");
  private static final Location DROP = Location.of("LOC-D", "Drop");

  private DriftFleet() {
    throw new IllegalStateException("utility");
  }

  static PlannerInput fortyOne(String tenant) {
    TenantId id = TenantId.of(tenant);
    return shell(id, 1)
        .addCab(cab(id, "CAB-A", 24_600L))
        .addCab(cab(id, "CAB-B", 24_600L))
        .addCab(cab(id, "CAB-C", 24_600L))
        .addTrip(trip(id, "TRIP-NEXT", 500, 10_000, 1_200))
        .addTrip(trip(id, "TRIP-LOCKED", 500, 10_000, 1_200))
        .addTrip(trip(id, "TRIP-LATER", 7_200, 10_000, 1_200))
        .build();
  }

  static PlannerInput twoPlugs(String tenant) {
    TenantId id = TenantId.of(tenant);
    return shell(id, 2)
        .addCharger(Charger.of(tenant, "CHG-2", "HUB-1", 50))
        .addCab(cab(id, "CAB-1", 12_000L))
        .addCab(cab(id, "CAB-2", 12_000L))
        .addTrip(trip(id, "TRIP-1", 3_600, 10_000, 1_200))
        .addTrip(trip(id, "TRIP-2", 3_600, 10_000, 1_200))
        .build();
  }

  static void publish(DataSource source, PlannerInput input) throws SQLException {
    ScenarioCatalog.store(source, input, SHARD);
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    SolverConfig config = SolverConfig.safeBaseline(START.plusSeconds(30), 11L);
    SolveResult solved = new GreedyConstructionSolver().solve(input, config, clock);
    LoadedFleet loaded = new FleetLoad(source).load(input.tenantId().value(), SHARD);
    PublishResult result =
        new PlanPublication(
                new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock)
            .publish(
                source,
                new PublishCommand(
                    input.tenantId(),
                    SHARD,
                    0L,
                    solved.plan(),
                    loaded.validation(),
                    "plan-seed",
                    1L,
                    0,
                    0,
                    0L,
                    0L,
                    "INITIAL",
                    FleetLowerBound.of(loaded.planner()).backups(),
                    solved.flow().relaxationMicros()));
    if (result.outcome() != PublishOutcome.PUBLISHED) {
      throw new IllegalStateException(result.outcome() + " " + result.report().toDetailedSummary());
    }
  }

  private static PlannerInput.Builder shell(TenantId tenant, int plugs) {
    Hub hub = Hub.of(tenant.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), plugs);
    return PlannerInput.builder()
        .tenantId(tenant)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(Charger.of(tenant.value(), "CHG-1", "HUB-1", 50))
        .distanceProvider(FleetDefaults.distance())
        .consumptionModel(FleetDefaults.consumption())
        .chargingCurve(FleetDefaults.curve());
  }

  static void slack(String tenant, String holder) throws SQLException {
    String sql =
        "UPDATE vehicle SET shift_start_soc_wh = 50000 WHERE tenant_id = ? AND vehicle_id <> ?";
    try (Connection connection = PlanDatabase.open().getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, tenant);
      statement.setString(2, holder);
      statement.executeUpdate();
    }
  }

  private static CabShift cab(TenantId tenant, String id, long socWh) {
    return new CabShift(
        new Vehicle(tenant, VehicleId.of(id), 60_000L, 150L, 50), DEPOT, socWh, START);
  }

  private static Trip trip(
      TenantId tenant, String id, long offsetSeconds, int meters, int durationSeconds) {
    Instant pickup = START.plusSeconds(offsetSeconds);
    return new Trip(
        tenant,
        TripId.of(id),
        pickup,
        PICKUP,
        DROP,
        meters,
        durationSeconds,
        pickup.plusSeconds(durationSeconds),
        "EMP-1");
  }
}
