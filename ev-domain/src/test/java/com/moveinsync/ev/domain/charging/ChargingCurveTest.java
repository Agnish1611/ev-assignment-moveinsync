package com.moveinsync.ev.domain.charging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChargingCurveTest {

  private final ChargingCurve curve = ChargingCurve.defaultCurve();
  // Standard 50 kWh battery pack
  private final long capacityWh = 50_000L;
  // 80% of 50,000 Wh is exactly 40,000 Wh
  private final long threshold80Wh = 40_000L;
  // 50 kW fast charger
  private final int plugPower50Kw = 50;

  @Test
  @DisplayName("Charging below 80% uses full fast power segment only")
  void chargingBelow80Percent() {
    // Charge from 10,000 Wh (20%) to 30,000 Wh (60%) = 20,000 Wh
    // Fast power = 50,000 W. Time = 20,000 * 3600 / 50,000 = 1,440 seconds (24 min)
    long duration = curve.calculateDurationSeconds(10_000L, 30_000L, capacityWh, plugPower50Kw);
    assertThat(duration).isEqualTo(1_440L);
  }

  @Test
  @DisplayName("Boundary at exactly 80%: charging up to 80% boundary uses only fast segment")
  void boundaryAtExactly80PercentTarget() {
    // Charge from 10,000 Wh (20%) to exactly 40,000 Wh (80%) = 30,000 Wh
    // Fast power = 50,000 W. Time = 30,000 * 3600 / 50,000 = 2,160 seconds (36 min)
    long duration =
        curve.calculateDurationSeconds(10_000L, threshold80Wh, capacityWh, plugPower50Kw);
    assertThat(duration).isEqualTo(2_160L);
  }

  @Test
  @DisplayName("Boundary at exactly 80%: charging starting at 80% boundary uses only slow segment")
  void boundaryAtExactly80PercentStart() {
    // Charge from exactly 40,000 Wh (80%) to 50,000 Wh (100%) = 10,000 Wh
    // Slow power = 50% of 50 kW = 25,000 W.
    // Time = 10,000 * 3600 / 25,000 = 1,440 seconds (24 min)
    long duration =
        curve.calculateDurationSeconds(threshold80Wh, capacityWh, capacityWh, plugPower50Kw);
    assertThat(duration).isEqualTo(1_440L);
  }

  @Test
  @DisplayName("Charging crossing 80% boundary sums fast and slow segments deterministically")
  void chargingCrossing80PercentBoundary() {
    // Charge from 20,000 Wh (40%) to 45,000 Wh (90%)
    // Fast segment: 20,000 Wh to 40,000 Wh = 20,000 Wh @ 50 kW -> 1,440s
    // Slow segment: 40,000 Wh to 45,000 Wh = 5,000 Wh @ 25 kW -> 720s
    // Total duration = 1,440 + 720 = 2,160s
    long duration = curve.calculateDurationSeconds(20_000L, 45_000L, capacityWh, plugPower50Kw);
    assertThat(duration).isEqualTo(2_160L);

    // Verify additivity across the boundary:
    long fastDuration =
        curve.calculateDurationSeconds(20_000L, threshold80Wh, capacityWh, plugPower50Kw);
    long slowDuration =
        curve.calculateDurationSeconds(threshold80Wh, 45_000L, capacityWh, plugPower50Kw);
    assertThat(duration).isEqualTo(fastDuration + slowDuration);
  }

  @Test
  @DisplayName("Conservative duration rounding rounds up to next whole second")
  void conservativeDurationRoundingUp() {
    // 1 Wh at 50,000 W: 1 * 3600 / 50,000 = 0.072 seconds -> rounds UP to 1 second
    long duration1Wh = curve.calculateDurationSeconds(10_000L, 10_001L, capacityWh, plugPower50Kw);
    assertThat(duration1Wh).isEqualTo(1L);

    // 0 Wh -> 0 seconds
    long duration0Wh = curve.calculateDurationSeconds(10_000L, 10_000L, capacityWh, plugPower50Kw);
    assertThat(duration0Wh).isEqualTo(0L);
  }

  @Test
  @DisplayName("Target SoC calculated from elapsed duration rounds down conservatively")
  void calculateTargetSocConservativeRoundingDown() {
    // Fast segment: 1,440 seconds from 10,000 Wh at 50 kW should deliver exactly 20,000 Wh ->
    // 30,000 Wh
    long reachedSoc = curve.calculateTargetSocWh(10_000L, 1_440L, capacityWh, plugPower50Kw);
    assertThat(reachedSoc).isEqualTo(30_000L);

    // Full charge up to threshold: 2,160 seconds from 10,000 Wh -> exactly 40,000 Wh
    long thresholdSoc = curve.calculateTargetSocWh(10_000L, 2_160L, capacityWh, plugPower50Kw);
    assertThat(thresholdSoc).isEqualTo(40_000L);

    // Crossing boundary: 2,160 seconds from 20,000 Wh -> exactly 45,000 Wh
    long crossedSoc = curve.calculateTargetSocWh(20_000L, 2_160L, capacityWh, plugPower50Kw);
    assertThat(crossedSoc).isEqualTo(45_000L);

    // Cap at total battery capacity
    long fullSoc = curve.calculateTargetSocWh(40_000L, 10_000L, capacityWh, plugPower50Kw);
    assertThat(fullSoc).isEqualTo(capacityWh);
  }

  @Test
  @DisplayName("Input validations reject illogical or out-of-bounds parameters")
  void inputValidations() {
    // Target less than start
    assertThatThrownBy(() -> curve.calculateDurationSeconds(20_000L, 10_000L, capacityWh, 50))
        .isInstanceOf(IllegalArgumentException.class);

    // Negative SoC
    assertThatThrownBy(() -> curve.calculateDurationSeconds(-1L, 10_000L, capacityWh, 50))
        .isInstanceOf(IllegalArgumentException.class);

    // Target exceeding battery capacity
    assertThatThrownBy(
            () -> curve.calculateDurationSeconds(10_000L, capacityWh + 1L, capacityWh, 50))
        .isInstanceOf(IllegalArgumentException.class);

    // Zero or negative power
    assertThatThrownBy(() -> curve.calculateDurationSeconds(10_000L, 20_000L, capacityWh, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
