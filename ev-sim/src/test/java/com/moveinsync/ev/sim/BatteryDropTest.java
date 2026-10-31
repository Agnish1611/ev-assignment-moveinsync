package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.LadderReplanSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cab that was planned at 41 percent reports 22 percent. The next pickup is one deadhead away,
 * so the trip has to move. Wall time is measured around the ladder only.
 */
class BatteryDropTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final long PLANNED_WH = 24_600L;
  private static final long READING_WH = 13_200L;
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");

  @Test
  void aFortyOnePercentPlanDropsToTwentyTwoPercent() throws IOException {
    Trip next = trip("TRIP-NEXT", 500L);
    Trip locked = trip("TRIP-LOCKED", 500L);
    Trip later = trip("TRIP-LATER", 7_200L);
    PlannerInput drifted = fleet(READING_WH, List.of(next, locked, later));
    FleetPlan current = published(drifted, next, locked, later);
    SolverConfig config = SolverConfig.safeBaseline(START.plusSeconds(2), 11L);
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    long started = System.nanoTime();
    SolveResult result =
        new LadderReplanSolver()
            .replan(
                drifted,
                current,
                new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", START),
                config,
                clock);
    long elapsedNanos = System.nanoTime() - started;
    ValidationReport report =
        new PlanValidator().validate(SimSupport.groundTruth(drifted), result.plan());

    assertThat(cabOf(result.plan(), "TRIP-NEXT")).isEqualTo("CAB-C");
    assertThat(cabOf(result.plan(), "TRIP-LOCKED")).isEqualTo("CAB-B");
    assertThat(cabOf(result.plan(), "TRIP-LATER")).isEqualTo("CAB-C");
    assertThat(result.score().changedAssignments()).isEqualTo(1);
    assertThat(result.plan().totalUnassignedTrips()).isZero();
    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
    write(elapsedNanos, result, report.isValid());
  }

  private static PlannerInput fleet(long cabA, List<Trip> trips) {
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(TENANT)
            .horizon(START, START.plusSeconds(28_800))
            .addHub(Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), 4))
            .addCharger(Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50))
            .addCab(cab("CAB-A", cabA))
            .addCab(cab("CAB-B", 50_000L))
            .addCab(cab("CAB-C", 50_000L))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    trips.forEach(builder::addTrip);
    return builder.build();
  }

  private static FleetPlan published(PlannerInput input, Trip next, Trip locked, Trip later) {
    return new FleetPlan(
        "plan-41",
        input.tenantId(),
        input.horizonStart(),
        input.horizonEnd(),
        List.of(
            schedule("CAB-A", next, PLANNED_WH),
            schedule("CAB-B", locked, 50_000L),
            schedule("CAB-C", later, 50_000L)),
        List.of(),
        11L,
        input.inputHash());
  }

  private static CabShift cab(String id, long socWh) {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of(id), 60_000L, 150L, 50), DEPOT, socWh, START);
  }

  private static Trip trip(String id, long offsetSeconds) {
    Instant pickup = START.plusSeconds(offsetSeconds);
    return new Trip(
        TENANT,
        TripId.of(id),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000,
        1_200,
        pickup.plusSeconds(1_200),
        "EMP-1");
  }

  private static VehicleSchedule schedule(String cabId, Trip assigned, long socWh) {
    TripAssignment assignment =
        new TripAssignment(
            assigned,
            VehicleId.of(cabId),
            assigned.startTs(),
            assigned.plannedDropoffTs(),
            0,
            0,
            0L,
            1L,
            1L);
    CabShift cab = cab(cabId, socWh);
    return new VehicleSchedule(
        cab.vehicle(), cab.startLocation(), socWh, cab.availableFrom(), List.of(assignment));
  }

  private static String cabOf(FleetPlan plan, String tripId) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment
            && assignment.trip().id().value().equals(tripId)) {
          return schedule.vehicleId().value();
        }
      }
    }
    return "";
  }

  private static void write(long elapsedNanos, SolveResult result, boolean valid)
      throws IOException {
    Path directory = Path.of("target");
    Files.createDirectories(directory);
    String metrics =
        "elapsedMillis="
            + elapsedNanos / 1_000_000L
            + "\nchanged="
            + result.score().changedAssignments()
            + "\nnext="
            + cabOf(result.plan(), "TRIP-NEXT")
            + "\nlocked="
            + cabOf(result.plan(), "TRIP-LOCKED")
            + "\nlater="
            + cabOf(result.plan(), "TRIP-LATER")
            + "\nunassigned="
            + result.plan().totalUnassignedTrips()
            + "\nsessions="
            + result.plan().totalChargingSessions()
            + "\nvalid="
            + valid
            + "\n";
    Files.writeString(directory.resolve("drift-22-metrics.txt"), metrics);
  }
}
