package com.moveinsync.ev.planner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.objective.ObjectiveScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SolverConfigTest {

  @Test
  @DisplayName("The baseline config uses the 18 percent floor and a 15 minute lock")
  void baselineUsesDocumentedDefaults() {
    SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);

    assertThat(config.planningFloorPercent()).isEqualTo(Vehicle.DEFAULT_ROBUST_MARGIN_PERCENT);
    assertThat(config.lockWindowSeconds()).isEqualTo(SolverConfig.DEFAULT_LOCK_WINDOW_SECONDS);
    assertThat(config.changePenaltyMicros()).isEqualTo(SolverConfig.DEFAULT_CHANGE_PENALTY_MICROS);
    assertThat(config.maxIterations()).isZero();
    assertThat(config.seed()).isEqualTo(7L);
  }

  @Test
  @DisplayName("A floor at the validator's 15 percent is rejected, and 17 percent is accepted")
  void floorBandIsEnforced() {
    assertThatThrownBy(
            () ->
                new SolverConfig(
                    PlannerFixtures.END,
                    1L,
                    15,
                    SolverConfig.DEFAULT_LOCK_WINDOW_SECONDS,
                    SolverConfig.DEFAULT_CHANGE_PENALTY_MICROS,
                    0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("planningFloorPercent");

    SolverConfig accepted =
        new SolverConfig(
            PlannerFixtures.END, 1L, 17, SolverConfig.DEFAULT_LOCK_WINDOW_SECONDS, 0L, 0);
    assertThat(accepted.planningFloorPercent()).isEqualTo(17);

    assertThatThrownBy(
            () ->
                new SolverConfig(
                    PlannerFixtures.END, 1L, 17, 0, SolverConfig.DEFAULT_CHANGE_PENALTY_MICROS, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lockWindowSeconds");
  }

  @Test
  @DisplayName("Fewer backups beat a cheaper plan, then the change penalty breaks remaining ties")
  void objectivePrefersFewerBackups() {
    ObjectiveScore covered = new ObjectiveScore(0, 2, 0L);
    ObjectiveScore backup = new ObjectiveScore(1, 0, 0L);
    long penalty = SolverConfig.DEFAULT_CHANGE_PENALTY_MICROS;

    assertThat(covered.comparePreferringFewerBackups(backup, penalty)).isNegative();
    assertThat(covered.secondaryMicros(penalty)).isEqualTo(2L * penalty);

    ObjectiveScore dear = new ObjectiveScore(0, 0, penalty + 1);
    assertThat(dear.comparePreferringFewerBackups(covered, penalty)).isNegative();
    assertThatThrownBy(() -> new ObjectiveScore(-1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
