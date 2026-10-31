package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.FleetLowerBound;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
    PlannerInput fleet = PricedScale.build();
    SolverConfig config = SolverConfig.safeBaseline(ScaleScenario.START.plusSeconds(30), 11L);
    Clock clock = Clock.fixed(ScaleScenario.START, ZoneOffset.UTC);
    long started = System.nanoTime();
    SolveResult result = new GreedyConstructionSolver().solve(fleet, config, clock);
    long elapsedNanos = System.nanoTime() - started;
    FleetLowerBound bound = FleetLowerBound.of(fleet);
    ValidationReport report =
        new PlanValidator().validate(SimSupport.groundTruth(fleet), result.plan());

    assertThat(fleet.cabs()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(fleet.trips()).hasSize(ScaleScenario.TRIP_COUNT);
    assertThat(fleet.chargers()).hasSize(ScaleScenario.CHARGER_COUNT);
    assertThat(elapsedNanos).isLessThan(LIMIT_NANOS);
    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
    assertThat(bound.backups()).isLessThanOrEqualTo(result.plan().totalUnassignedTrips());
    assertThat(result.flow().relaxationMicros())
        .isLessThanOrEqualTo(result.plan().totalChargingCostMicros());
    assertThat(result.plan().totalAssignedTrips()).isEqualTo(1_350);
    assertThat(result.plan().totalUnassignedTrips()).isEqualTo(150);
    assertThat(result.plan().totalChargingSessions()).isEqualTo(4);
    assertThat(result.plan().totalChargingCostMicros()).isEqualTo(20_000_000L);
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
            + "\nsessions="
            + result.plan().totalChargingSessions()
            + "\n"
            + sessions(result)
            + sample(result)
            + "\n";
    Files.writeString(directory.resolve("planner-300cab-metrics.txt"), metrics);
  }

  private static String sessions(SolveResult result) {
    StringBuilder lines = new StringBuilder();
    for (VehicleSchedule schedule : result.plan().vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          lines
              .append("session=")
              .append(schedule.vehicleId().value())
              .append(' ')
              .append(session.startTime())
              .append(' ')
              .append(session.endTime())
              .append(' ')
              .append(session.hubId().value())
              .append(' ')
              .append(session.chargerId().value())
              .append(' ')
              .append(session.startSocWh())
              .append(' ')
              .append(session.targetSocWh())
              .append(' ')
              .append(session.costMicros())
              .append('\n');
        }
      }
    }
    return lines.toString();
  }

  private static String sample(SolveResult result) {
    for (VehicleSchedule schedule : result.plan().vehicleSchedules()) {
      String line = sandwiched(schedule);
      if (!line.isEmpty()) {
        return line;
      }
    }
    return "sample=";
  }

  private static String sandwiched(VehicleSchedule schedule) {
    boolean tripBefore = false;
    boolean tripAfter = false;
    String beforeId = "";
    String afterId = "";
    String charge = "";
    Instant chargeEnd = null;
    for (RouteActivity activity : schedule.activities()) {
      if (activity instanceof TripAssignment trip) {
        if (chargeEnd == null) {
          tripBefore = true;
          beforeId = trip.trip().id().value();
        } else if (!trip.trip().startTs().isBefore(chargeEnd)) {
          tripAfter = true;
          if (afterId.isEmpty()) {
            afterId = trip.trip().id().value();
          }
        }
      } else if (activity instanceof ChargingSession session && charge.isEmpty()) {
        chargeEnd = session.endTime();
        charge =
            session.startTime()
                + " "
                + session.endTime()
                + " "
                + session.hubId().value()
                + " "
                + session.chargerId().value()
                + " "
                + session.startSocWh()
                + " "
                + session.targetSocWh()
                + " "
                + session.costMicros();
      }
    }
    if (!tripBefore || charge.isEmpty() || !tripAfter) {
      return "";
    }
    StringBuilder steps = new StringBuilder();
    for (RouteActivity activity : schedule.activities()) {
      if (activity instanceof TripAssignment trip) {
        steps
            .append("step=")
            .append(schedule.vehicleId().value())
            .append(" trip ")
            .append(trip.trip().id().value())
            .append(' ')
            .append(trip.pickupTime())
            .append(' ')
            .append(trip.dropoffTime())
            .append(' ')
            .append(trip.trip().distanceMeters())
            .append(' ')
            .append(trip.departureAfterDropoffSocWh())
            .append('\n');
      }
    }
    return "sample="
        + schedule.vehicleId().value()
        + " "
        + beforeId
        + " "
        + afterId
        + " "
        + charge
        + "\n"
        + steps;
  }
}
