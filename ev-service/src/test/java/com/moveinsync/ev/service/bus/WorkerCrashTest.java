package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Closing the worker mid-solve does not commit the sequence. The replacement accepts that same
 * sequence from the retry topic.
 */
class WorkerCrashTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void aKilledSolveIsRetriedByTheReplacement() throws InterruptedException {
    MemoryMessageBus bus = new MemoryMessageBus();
    MovingClock clock = new MovingClock(START);
    SeenSequences seen = new SeenSequences();
    Delivery delivery = new Delivery(bus, seen, clock, BusSettings.standard());
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch killed = new CountDownLatch(1);
    AtomicInteger finished = new AtomicInteger();
    PlannerWorker worker = new PlannerWorker(bus, delivery, BusSettings.standard());
    worker.start(envelope -> blockUntilKilled(entered, killed, finished));

    bus.send(trigger(1L));
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    worker.close();
    assertThat(killed.await(2, TimeUnit.SECONDS)).isTrue();
    waitForRetry(bus);
    assertThat(seen.preview("tenant-a", "device", 1L)).isEqualTo(SeenSequences.Disposition.ACCEPT);

    CountDownLatch done = new CountDownLatch(1);
    PlannerWorker replacement = new PlannerWorker(bus, delivery, BusSettings.standard());
    replacement.start(
        envelope -> {
          finished.incrementAndGet();
          done.countDown();
        });
    clock.advance(Duration.ofSeconds(5));
    bus.releaseDue(clock.instant());

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(finished).hasValue(1);
    assertThat(seen.preview("tenant-a", "device", 1L))
        .isEqualTo(SeenSequences.Disposition.DUPLICATE);
    replacement.close();
  }

  private static void blockUntilKilled(
      CountDownLatch entered, CountDownLatch killed, AtomicInteger finished) {
    entered.countDown();
    try {
      if (!new CountDownLatch(1).await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("solve was not killed");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      killed.countDown();
      throw new IllegalStateException("killed");
    }
    finished.incrementAndGet();
  }

  private static void waitForRetry(MemoryMessageBus bus) {
    long until = System.nanoTime() + 2_000_000_000L;
    while (bus.queued() == 0L) {
      if (System.nanoTime() > until) {
        throw new IllegalStateException("retry was not deferred");
      }
      Thread.onSpinWait();
    }
  }

  private static BusRecord trigger(long sequence) {
    Envelope envelope =
        new Envelope(
            "tenant-a:device:" + sequence,
            "tenant-a",
            "device",
            sequence,
            START,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger("LATE_CAB", "CAB-1", "shift-1", START, OptionalLong.empty()));
    return BusRecord.of(Topics.PLAN_TRIGGERS, "tenant-a:shift-1", EnvelopeCodec.write(envelope));
  }

  private static final class MovingClock extends Clock {
    private Instant current;

    private MovingClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public Instant instant() {
      return current;
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
