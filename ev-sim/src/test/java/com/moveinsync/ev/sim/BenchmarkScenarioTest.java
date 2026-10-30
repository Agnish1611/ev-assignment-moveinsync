package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.FleetLowerBound;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import com.moveinsync.ev.validator.ValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Solves the 300-cab case and checks it with the independent validator. Wall time is the benchmark
 * measurement; the solver clock stays fixed so the plan does not depend on the machine.
 */
class BenchmarkScenarioTest {

  private static final long LIMIT_NANOS = 30_000_000_000L;

  @Test
  void threeHundredCabsSolveWithinThirtySeconds() throws IOException {
    PlannerInput fleet = ScaleScenario.build();
    SolverConfig config = SolverConfig.safeBaseline(ScaleScenario.START.plusSeconds(30), 11L);
    Clock clock = Clock.fixed(ScaleScenario.START, ZoneOffset.UTC);
    long started = System.nanoTime();
    SolveResult result = new GreedyConstructionSolver().solve(fleet, config, clock);
    long elapsedNanos = System.nanoTime() - started;
    FleetLowerBound bound = FleetLowerBound.of(fleet);
    ValidationReport report = new PlanValidator().validate(groundTruth(fleet), result.plan());

    assertThat(fleet.cabs()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(fleet.trips()).hasSize(ScaleScenario.TRIP_COUNT);
    assertThat(fleet.chargers()).hasSize(ScaleScenario.CHARGER_COUNT);
    assertThat(elapsedNanos).isLessThan(LIMIT_NANOS);
    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
    assertThat(bound.backups()).isLessThanOrEqualTo(result.plan().totalUnassignedTrips());
    assertThat(result.flow().relaxationMicros())
        .isLessThanOrEqualTo(result.plan().totalChargingCostMicros());
    assertThat(result.plan().totalAssignedTrips() + result.plan().totalUnassignedTrips())
        .isEqualTo(ScaleScenario.TRIP_COUNT);
    writeMetrics(elapsedNanos, result, bound, report.isValid());
  }

  private static void writeMetrics(
      long elapsedNanos, SolveResult result, FleetLowerBound bound, boolean valid)
      throws IOException {
    Path directory = Path.of("target");
    Files.createDirectories(directory);
    String metrics =
        "elapsedMillis="
            + elapsedNanos / 1_000_000L
            + "\nassigned="
            + result.plan().totalAssignedTrips()
            + "\nunassigned="
            + result.plan().totalUnassignedTrips()
            + "\nintrinsic="
            + bound.intrinsicBackups()
            + "\noverlap="
            + bound.overlapBackups()
            + "\nenergy="
            + bound.energyBackups()
            + "\nbackupLower="
            + bound.backups()
            + "\ncostFloorMicros="
            + bound.minimumCostMicros()
            + "\nrelaxationMicros="
            + result.flow().relaxationMicros()
            + "\nachievedCostMicros="
            + result.plan().totalChargingCostMicros()
            + "\nvalid="
            + valid
            + "\n";
    Files.writeString(directory.resolve("planner-300cab-metrics.txt"), metrics);
  }

  private static ValidationInput groundTruth(PlannerInput fleet) {
    ValidationInputBuilder builder =
        new ValidationInputBuilder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .distanceProvider(fleet.distanceProvider())
            .consumptionModel(fleet.consumptionModel())
            .chargingCurve(fleet.chargingCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .idlePowerW(0);
    fleet
        .cabs()
        .forEach(cab -> builder.addVehicle(cab.vehicle(), cab.startLocation(), cab.initialSocWh()));
    fleet.trips().forEach(builder::addTrip);
    fleet.hubs().forEach(builder::addHub);
    fleet.chargers().forEach(builder::addCharger);
    return builder.build();
  }
}
