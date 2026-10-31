package com.moveinsync.ev.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A user spends one token per call and is refused at the capacity. */
class RateGateTest {

  @Test
  void theThirdCallInAWindowIsRefused() {
    RateGate gate = new RateGate(new ApiSettings(2, 60, 8192, 64, 500, 64, 2000));

    assertThat(gate.allow("user-1")).isTrue();
    assertThat(gate.allow("user-1")).isTrue();
    assertThat(gate.allow("user-1")).isFalse();
    assertThat(gate.allow("user-2")).isTrue();
  }
}
