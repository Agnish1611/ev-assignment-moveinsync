package com.moveinsync.ev.planner.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Hysteresis, quarantine, cooldown, and silent decay. No broker. */
class TelemetryGateTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final long EXPECTED = 24_600L;

  @Test
  void aReadingInsideTheBandDoesNotTrigger() {
    TelemetryGate gate = gate();

    assertThat(gate.observe("CAB-1", 24_000L, OptionalLong.of(EXPECTED), START)).isEmpty();
  }

  @Test
  void aFirstReadingFarFromThePlanTriggers() {
    TelemetryGate gate = gate();

    DriftEvent event =
        gate.observe("CAB-1", 13_200L, OptionalLong.of(EXPECTED), START).orElseThrow();

    assertThat(event.kind()).isEqualTo(DriftEvent.Kind.BATTERY);
    assertThat(event.subjectId()).isEqualTo("CAB-1");
    assertThat(event.reportedSocWh()).hasValue(13_200L);
  }

  @Test
  void aFastJumpIsQuarantinedAndDoesNotReplaceTheLastReading() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", 20_000L, OptionalLong.of(20_000L), START);

    assertThat(gate.observe("CAB-1", 40_000L, OptionalLong.of(20_000L), START.plusSeconds(1)))
        .isEmpty();

    assertThat(gate.observe("CAB-1", 20_000L, OptionalLong.of(20_000L), START.plusSeconds(10)))
        .isEmpty();
  }

  @Test
  void aReadingBelowTheCriticalFloorTriggersEvenWhenTheJumpIsWild() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", 20_000L, OptionalLong.of(20_000L), START);

    DriftEvent event =
        gate.observe("CAB-1", 8_000L, OptionalLong.of(20_000L), START.plusSeconds(1)).orElseThrow();

    assertThat(event.reportedSocWh()).hasValue(8_000L);
  }

  @Test
  void aSecondDriftInsideTheCooldownIsHeld() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", 13_200L, OptionalLong.of(EXPECTED), START);

    assertThat(gate.observe("CAB-1", 12_200L, OptionalLong.of(EXPECTED), START.plusSeconds(20)))
        .isEmpty();
  }

  @Test
  void aDriftAfterTheCooldownTriggersAgain() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", 13_200L, OptionalLong.of(EXPECTED), START);

    assertThat(gate.observe("CAB-1", 12_000L, OptionalLong.of(EXPECTED), START.plusSeconds(330)))
        .isPresent();
  }

  @Test
  void aCriticalReadingInsideTheCooldownStillTriggers() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", 13_200L, OptionalLong.of(EXPECTED), START);

    assertThat(gate.observe("CAB-1", 8_000L, OptionalLong.of(EXPECTED), START.plusSeconds(10)))
        .isPresent();
  }

  @Test
  void silenceDecaysTheEstimateAndTriggers() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", EXPECTED, OptionalLong.of(EXPECTED), START);

    assertThat(gate.silence(START.plusSeconds(119))).isEmpty();
    List<DriftEvent> decayed = gate.silence(START.plusSeconds(120));
    assertThat(decayed).hasSize(1);
    assertThat(decayed.get(0).reportedSocWh()).hasValue(23_400L);
  }

  @Test
  void anOldTimestampIsIgnored() {
    TelemetryGate gate = gate();
    gate.observe("CAB-1", EXPECTED, OptionalLong.of(EXPECTED), START.plusSeconds(10));

    assertThat(gate.observe("CAB-1", 1_000L, OptionalLong.of(EXPECTED), START)).isEmpty();
  }

  @Test
  void aReadingWithoutAPlanDoesNotTrigger() {
    TelemetryGate gate = gate();

    assertThat(gate.observe("CAB-1", 1_000L, OptionalLong.empty(), START)).isEmpty();
    assertThat(gate.silence(START.plusSeconds(600))).isEmpty();
  }

  @Test
  void silenceBeforeAnyReadingIsEmpty() {
    assertThat(gate().silence(START)).isEmpty();
  }

  @Test
  void aNegativeOrPersonalReadingIsRejected() {
    TelemetryGate gate = gate();

    assertThatThrownBy(() -> gate.observe("CAB-1", -1L, OptionalLong.empty(), START))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> gate.observe("a@b", 1L, OptionalLong.empty(), START))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TelemetryPolicy(0L, 1L, 0L, 0L, 1L, 1L, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TelemetryGate gate() {
    return new TelemetryGate(TelemetryPolicy.standard());
  }
}
