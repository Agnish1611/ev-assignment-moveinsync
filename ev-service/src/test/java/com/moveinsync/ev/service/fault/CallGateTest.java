package com.moveinsync.ev.service.fault;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Three failures open the gate. One probe is allowed after the open interval. */
class CallGateTest {

  @Test
  void failuresOpenTheGateUntilTheIntervalPasses() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-30T08:00:00Z"));
    CallGate gate = new CallGate(2, 1_000L, clock);

    gate.failure();
    assertThatCode(gate::enter).doesNotThrowAnyException();
    gate.failure();
    assertThatThrownBy(gate::enter).isInstanceOf(CallBlocked.class);

    clock.advance(999L);
    assertThatThrownBy(gate::enter).isInstanceOf(CallBlocked.class);
    clock.advance(1L);
    assertThatCode(gate::enter).doesNotThrowAnyException();
    assertThatThrownBy(gate::enter).isInstanceOf(CallBlocked.class);

    gate.success();
    assertThatCode(gate::enter).doesNotThrowAnyException();
  }

  @Test
  void aFailedProbeStartsTheOpenIntervalAgain() {
    MutableClock clock = new MutableClock(Instant.parse("2026-10-30T08:00:00Z"));
    CallGate gate = new CallGate(1, 1_000L, clock);

    gate.failure();
    clock.advance(1_000L);
    gate.enter();
    gate.failure();
    assertThatThrownBy(gate::enter).isInstanceOf(CallBlocked.class);
    clock.advance(999L);
    assertThatThrownBy(gate::enter).isInstanceOf(CallBlocked.class);
    clock.advance(1L);
    assertThatCode(gate::enter).doesNotThrowAnyException();
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant current) {
      this.current = current;
    }

    private void advance(long millis) {
      current = current.plusMillis(millis);
    }

    @Override
    public Instant instant() {
      return current;
    }

    @Override
    public long millis() {
      return current.toEpochMilli();
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
