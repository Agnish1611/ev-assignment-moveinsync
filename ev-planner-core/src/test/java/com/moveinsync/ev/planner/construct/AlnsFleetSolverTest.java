package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.SolveResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlnsFleetSolverTest {

  private final AlnsFleetSolver solver = new AlnsFleetSolver();
  private final PlannerInput fleet = PlannerFixtures.sample();
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void theSameSeedProducesTheSamePlan() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L).withIterations(12);

    SolveResult first = solver.solve(fleet, config, early);
    SolveResult second = solver.solve(fleet, config, early);

    assertThat(first.plan().planId()).startsWith("alns-");
    assertThat(first.plan().seed()).isEqualTo(11L);
    assertThat(first.deadlineExhausted()).isFalse();
    assertThat(vehicles(first)).isEqualTo(vehicles(second));
    assertThat(
            first
                .score()
                .comparePreferringFewerBackups(second.score(), config.changePenaltyMicros()))
        .isZero();
  }

  @Test
  void searchDoesNotAddBackups() {
    SolverConfig baseline = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);
    SolverConfig search = baseline.withIterations(12);
    SolveResult greedy = new GreedyConstructionSolver().solve(fleet, baseline, early);

    SolveResult searched = solver.solve(fleet, search, early);

    assertThat(
            searched
                .score()
                .comparePreferringFewerBackups(greedy.score(), baseline.changePenaltyMicros()))
        .isLessThanOrEqualTo(0);
    assertThat(searched.plan().totalAssignedTrips()).isEqualTo(2);
  }

  @Test
  void zeroIterationsReturnsConstruction() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);

    SolveResult result = solver.solve(fleet, config, early);

    assertThat(result.plan().planId()).startsWith("greedy-");
    assertThat(result.plan().totalAssignedTrips()).isEqualTo(2);
    assertThat(result.deadlineExhausted()).isFalse();
  }

  @Test
  void aDeadlineThatHasAlreadyPassedReturnsBackup() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L).withIterations(4);
    Clock late = Clock.fixed(PlannerFixtures.END, ZoneOffset.UTC);

    SolveResult result = solver.solve(fleet, config, late);

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(result.plan().planId()).startsWith("backup-");
    assertThat(result.plan().totalAssignedTrips()).isZero();
  }

  @Test
  void theSearchStopsWhenTheDeadlinePassesMidLoop() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L).withIterations(30);

    SolveResult result = solver.solve(fleet, config, new EarlyThenLate());

    assertThat(result.deadlineExhausted()).isTrue();
    assertThat(result.plan().planId()).startsWith("alns-");
    assertThat(result.plan().totalAssignedTrips()).isEqualTo(2);
  }

  @Test
  void nullArgumentsAndANonPositiveCapAreRejected() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 11L);

    assertThatThrownBy(() -> solver.solve(null, config, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.solve(fleet, null, early))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> solver.solve(fleet, config, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> config.withIterations(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, String> vehicles(SolveResult result) {
    Map<String, String> found = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, TripAssignment> entry : PlanViews.byTrip(result.plan()).entrySet()) {
      found.put(entry.getKey(), entry.getValue().vehicleId().value());
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
      return seen < 10 ? PlannerFixtures.START : PlannerFixtures.END;
    }
  }
}
