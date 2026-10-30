package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Construction at 1x, 2x, 5x, and 10x a 10-cab base. Each point stays inside 30 seconds and passes
 * the validator. The 300-cab case stays in {@link BenchmarkScenarioTest}.
 */
class ScaleCurveTest {

  private static final int BASE_CABS = 10;
  private static final int[] FACTORS = {1, 2, 5, 10};
  private static final long LIMIT_NANOS = 30_000_000_000L;

  @Test
  void theFullEveningFleetMatchesTheSizedCall() {
    assertThat(ScaleScenario.fleet(300, 40, 5).inputHash())
        .isEqualTo(ScaleScenario.build().inputHash());
    assertThatThrownBy(() -> ScaleScenario.fleet(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructionScalesFromOneToTenTimesAndStaysValid() throws IOException {
    List<String> lines = new ArrayList<>();
    Clock clock = Clock.fixed(ScaleScenario.START, ZoneOffset.UTC);
    SolverConfig config = SolverConfig.safeBaseline(ScaleScenario.START.plusSeconds(30), 11L);
    for (int factor : FACTORS) {
      lines.add(measure(factor, config, clock));
    }
    Path directory = Path.of("target");
    Files.createDirectories(directory);
    Files.write(directory.resolve("scale-curve.txt"), lines);
  }

  private static String measure(int factor, SolverConfig config, Clock clock) {
    int cabs = BASE_CABS * factor;
    int chargers = Math.max(1, cabs * ScaleScenario.CHARGER_COUNT / ScaleScenario.CAB_COUNT);
    PlannerInput fleet = ScaleScenario.fleet(cabs, chargers, ScaleScenario.TRIPS_PER_CAB);
    long started = System.nanoTime();
    SolveResult result = new GreedyConstructionSolver().solve(fleet, config, clock);
    long elapsed = System.nanoTime() - started;
    ValidationReport report =
        new PlanValidator().validate(SimSupport.groundTruth(fleet), result.plan());

    assertThat(fleet.cabs()).hasSize(cabs);
    assertThat(fleet.trips()).hasSize(cabs * ScaleScenario.TRIPS_PER_CAB);
    assertThat(elapsed).isLessThan(LIMIT_NANOS);
    assertThat(result.deadlineExhausted()).isFalse();
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
    return "factor="
        + factor
        + " cabs="
        + cabs
        + " chargers="
        + chargers
        + " trips="
        + fleet.trips().size()
        + " elapsedMillis="
        + elapsed / 1_000_000L
        + " assigned="
        + result.plan().totalAssignedTrips()
        + " unassigned="
        + result.plan().totalUnassignedTrips()
        + " valid=true";
  }
}
