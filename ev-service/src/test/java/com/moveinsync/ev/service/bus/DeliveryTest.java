package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Duplicate, late, and poison records never reach a second solve. The clock moves. Nothing sleeps.
 */
class DeliveryTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void aDuplicateSequenceIsDropped() {
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = delivery(bus, new MovingClock(START));
    AtomicInteger solves = new AtomicInteger();
    bus.subscribe(Topics.PLAN_TRIGGERS, (record, ack) -> accept(delivery, record, solves));

    bus.send(trigger(1L));
    bus.send(trigger(1L));

    assertThat(solves).hasValue(1);
  }

  @Test
  void anOlderSequenceIsDropped() {
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = delivery(bus, new MovingClock(START));
    AtomicInteger solves = new AtomicInteger();
    bus.subscribe(Topics.PLAN_TRIGGERS, (record, ack) -> accept(delivery, record, solves));

    bus.send(trigger(5L));
    bus.send(trigger(4L));

    assertThat(solves).hasValue(1);
  }

  @Test
  void aReorderedBurstKeepsTheHighestThenTheNext() {
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = delivery(bus, new MovingClock(START));
    AtomicInteger solves = new AtomicInteger();
    bus.subscribe(Topics.PLAN_TRIGGERS, (record, ack) -> accept(delivery, record, solves));

    bus.send(trigger(3L));
    bus.send(trigger(1L));
    bus.send(trigger(2L));
    bus.send(trigger(4L));

    assertThat(solves).hasValue(2);
  }

  @Test
  void threeFailuresEndOnTheDeadLetterTopic() {
    MemoryMessageBus bus = new MemoryMessageBus();
    MovingClock clock = new MovingClock(START);
    Delivery delivery = delivery(bus, clock);
    AtomicInteger solves = new AtomicInteger();
    List<String> dead = new ArrayList<>();
    bus.subscribe(
        Topics.deadLetter(Topics.PLAN_TRIGGERS),
        (record, ack) -> {
          dead.add(record.topic());
          ack.run();
        });
    bus.subscribe(
        Topics.PLAN_TRIGGERS,
        (record, ack) -> {
          delivery.attempt(
              record,
              envelope -> {
                solves.incrementAndGet();
                throw new IllegalStateException("solve failed");
              });
          ack.run();
        });

    bus.send(trigger(1L));
    clock.advance(Duration.ofSeconds(5));
    bus.releaseDue(clock.instant());
    clock.advance(Duration.ofSeconds(60));
    bus.releaseDue(clock.instant());

    assertThat(solves).hasValue(3);
    assertThat(dead).containsExactly(Topics.deadLetter(Topics.PLAN_TRIGGERS));
  }

  @Test
  void aPoisonPayloadIsDeadLettered() {
    MemoryMessageBus bus = new MemoryMessageBus();
    MovingClock clock = new MovingClock(START);
    Delivery delivery = delivery(bus, clock);
    AtomicInteger solves = new AtomicInteger();
    List<byte[]> dead = new ArrayList<>();
    bus.subscribe(
        Topics.deadLetter(Topics.PLAN_TRIGGERS),
        (record, ack) -> {
          dead.add(record.value());
          ack.run();
        });
    bus.subscribe(
        Topics.PLAN_TRIGGERS,
        (record, ack) -> {
          delivery.attempt(record, envelope -> solves.incrementAndGet());
          ack.run();
        });

    bus.send(BusRecord.of(Topics.PLAN_TRIGGERS, "tenant-a:shift-1", new byte[] {0x08, 0x01}));
    clock.advance(Duration.ofSeconds(5));
    bus.releaseDue(clock.instant());
    clock.advance(Duration.ofSeconds(60));
    bus.releaseDue(clock.instant());

    assertThat(solves).hasValue(0);
    assertThat(dead).hasSize(1);
  }

  @Test
  void thePollIntervalOutlastsTheLongestSolve() {
    BusSettings settings = BusSettings.standard();

    assertThat(settings.maxPollIntervalMillis()).isGreaterThan(settings.longestSolveMillis());
    assertThat(settings.longestSolveMillis()).isGreaterThanOrEqualTo(30_000L);
    assertThatThrownBy(() -> new BusSettings(1, 1, 30_000L, 30_000L, 5L, 60L, 200L, 10_000L, 8))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void accept(Delivery delivery, BusRecord record, AtomicInteger solves) {
    delivery.attempt(record, envelope -> solves.incrementAndGet());
  }

  private static Delivery delivery(MemoryMessageBus bus, Clock clock) {
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, BusSettings.standard());
    delivery.install(Topics.PLAN_TRIGGERS);
    return delivery;
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
