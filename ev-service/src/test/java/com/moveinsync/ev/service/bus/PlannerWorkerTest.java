package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The solve runs off the caller thread, and a second trigger waits until the first one finishes.
 */
class PlannerWorkerTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void theSolveRunsOnASolverThread() throws InterruptedException {
    MemoryMessageBus bus = new MemoryMessageBus();
    PlannerWorker worker = worker(bus);
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> thread = new AtomicReference<>();
    worker.start(
        envelope -> {
          thread.set(Thread.currentThread().getName());
          done.countDown();
        });

    bus.send(trigger(1L));

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(thread.get()).startsWith("planner-solve-");
    assertThat(thread.get()).isNotEqualTo(Thread.currentThread().getName());
    worker.close();
  }

  @Test
  void aPausedTopicHoldsTheNextTrigger() throws InterruptedException {
    MemoryMessageBus bus = new MemoryMessageBus();
    PlannerWorker worker = worker(bus);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(2);
    AtomicInteger running = new AtomicInteger();
    worker.start(
        envelope -> {
          running.incrementAndGet();
          started.countDown();
          await(release);
          finished.countDown();
        });

    bus.send(trigger(1L));
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    bus.send(trigger(2L));
    assertThat(running).hasValue(1);

    release.countDown();
    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(running).hasValue(2);
    assertThat(PlannerWorker.drift(read(trigger(2L))).kind()).isEqualTo(DriftEvent.Kind.LATE_CAB);
    worker.close();
  }

  @Test
  void aFullQueueRetriesInsteadOfDropping() throws InterruptedException {
    EagerBus bus = new EagerBus();
    BusSettings settings = new BusSettings(1, 1, 120_000L, 30_000L, 5L, 60L, 200L, 10_000L, 8, 10);
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, settings);
    PlannerWorker worker = new PlannerWorker(bus, delivery, settings);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    worker.start(
        envelope -> {
          started.countDown();
          await(release);
        });

    bus.send(trigger(1L));
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    bus.send(trigger(2L));
    bus.send(trigger(3L));

    assertThat(bus.retried()).isEqualTo(1);
    release.countDown();
    worker.close();
  }

  private static PlannerWorker worker(MemoryMessageBus bus) {
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, BusSettings.standard());
    return new PlannerWorker(bus, delivery, BusSettings.standard());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("solve was not released");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("solve was interrupted", ex);
    }
  }

  private static Envelope read(BusRecord record) {
    return EnvelopeCodec.read(record.value());
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

  private static final class EagerBus implements MessageBus {
    private final Map<String, RecordHandler> handlers = new HashMap<>();
    private int retried;

    @Override
    public void send(BusRecord record) {
      if (Topics.retryShort(Topics.PLAN_TRIGGERS).equals(record.topic())) {
        retried++;
        return;
      }
      RecordHandler handler = handlers.get(record.topic());
      if (handler != null) {
        handler.onRecord(record, () -> {});
      }
    }

    @Override
    public void subscribe(String topic, RecordHandler handler) {
      handlers.put(topic, handler);
    }

    @Override
    public void pause(String topic) {
      // Delivery is not paused, so a full solver queue can reject.
    }

    @Override
    public void resume(String topic) {
      // The test releases the blocked solve itself.
    }

    @Override
    public void defer(BusRecord record, Instant until) {
      // Retries are counted in send.
    }

    @Override
    public void releaseDue(Instant now) {
      // Nothing is deferred.
    }

    @Override
    public void close() {
      // No broker to stop.
    }

    private int retried() {
      return retried;
    }
  }
}
