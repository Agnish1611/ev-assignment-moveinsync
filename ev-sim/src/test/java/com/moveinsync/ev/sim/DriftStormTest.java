package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.construct.LadderReplanSolver;
import com.moveinsync.ev.planner.construct.ReplanBatch;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.ReplanSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One hundred cabs report at the same moment. The batch stops at the two-second deadline and the
 * plan it returns still passes the validator.
 */
class DriftStormTest {

  private static final int CABS = 100;
  private static final int CHARGERS = 8;
  private static final long DEADLINE_SECONDS = 2L;
  private static final long SLACK_NANOS = 3_000_000_000L;

  @Test
  void aHundredCabStormStopsAtTheDeadlineAndStaysValid() {
    PlannerInput fleet = ScaleScenario.fleet(CABS, CHARGERS, 1);
    FleetPlan current = constructed(fleet);
    List<DriftEvent> events = readings(fleet);
    AtomicInteger applied = new AtomicInteger();
    ReplanSolver counting = counting(applied);
    Clock clock = new LiveClock(ScaleScenario.START);
    SolverConfig config =
        SolverConfig.safeBaseline(ScaleScenario.START.plusSeconds(DEADLINE_SECONDS), 11L);

    long started = System.nanoTime();
    SolveResult repaired = ReplanBatch.apply(counting, fleet, current, events, config, clock);
    long elapsed = System.nanoTime() - started;
    ValidationReport report =
        new PlanValidator().validate(SimSupport.groundTruth(fleet), repaired.plan());

    assertThat(events).hasSize(CABS * 2);
    assertThat(applied).hasValueLessThanOrEqualTo(CABS);
    assertThat(elapsed).isLessThan(SLACK_NANOS);
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
    assertThat(repaired.plan().totalAssignedTrips() + repaired.plan().totalUnassignedTrips())
        .isEqualTo(CABS);
    if (applied.get() < CABS) {
      assertThat(repaired.deadlineExhausted()).isTrue();
    }
  }

  private static FleetPlan constructed(PlannerInput fleet) {
    SolverConfig config = SolverConfig.safeBaseline(ScaleScenario.START.plusSeconds(30), 11L);
    Clock clock = Clock.fixed(ScaleScenario.START, ZoneOffset.UTC);
    SolveResult result = new GreedyConstructionSolver().solve(fleet, config, clock);
    assertThat(result.deadlineExhausted()).isFalse();
    return result.plan();
  }

  private static List<DriftEvent> readings(PlannerInput fleet) {
    List<DriftEvent> events = new ArrayList<>();
    for (CabShift cab : fleet.cabs()) {
      String cabId = cab.vehicle().id().value();
      events.add(new DriftEvent(DriftEvent.Kind.BATTERY, cabId, ScaleScenario.START));
      events.add(
          new DriftEvent(DriftEvent.Kind.BATTERY, cabId, ScaleScenario.START.plusSeconds(1)));
    }
    return events;
  }

  private static ReplanSolver counting(AtomicInteger applied) {
    LadderReplanSolver solver = new LadderReplanSolver();
    return (input, current, event, config, clock) -> {
      applied.incrementAndGet();
      return solver.replan(input, current, event, config, clock);
    };
  }

  private static final class LiveClock extends Clock {
    private final Instant origin;
    private final long started = System.nanoTime();

    private LiveClock(Instant origin) {
      this.origin = origin;
    }

    @Override
    public Instant instant() {
      return origin.plusNanos(System.nanoTime() - started);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
