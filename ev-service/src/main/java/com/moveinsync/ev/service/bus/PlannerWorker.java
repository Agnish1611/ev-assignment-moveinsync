package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Single consumer of plan triggers. The poll thread only queues work. The partition stays paused
 * until the solve finishes, and the offset is committed only then.
 */
public final class PlannerWorker implements AutoCloseable {

  private final MessageBus bus;
  private final Delivery delivery;
  private final ExecutorService executor;
  private final String topic;

  public PlannerWorker(MessageBus bus, Delivery delivery, BusSettings settings) {
    this(bus, delivery, settings, Topics.PLAN_TRIGGERS);
  }

  PlannerWorker(MessageBus bus, Delivery delivery, BusSettings settings, String topic) {
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.delivery = Objects.requireNonNull(delivery, "delivery must not be null");
    this.topic = topic;
    this.executor =
        new ThreadPoolExecutor(
            settings.solveThreads(),
            settings.solveThreads(),
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(settings.solveQueue()),
            named(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  public void start(Delivery.EnvelopeSink sink) {
    delivery.install(topic);
    bus.subscribe(topic, (record, ack) -> submit(record, ack, sink));
  }

  public void watchDeadLetters(Consumer<BusRecord> dead) {
    bus.subscribe(Topics.deadLetter(topic), (record, ack) -> dead(record, ack, dead));
  }

  public static DriftEvent drift(Envelope envelope) {
    if (!(envelope.payload() instanceof Envelope.PlanTrigger trigger)) {
      throw new IllegalArgumentException("trigger payload is required");
    }
    return new DriftEvent(
        DriftEvent.Kind.valueOf(trigger.kind()),
        trigger.subjectId(),
        trigger.observedAt(),
        trigger.reportedSocWh());
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private void submit(BusRecord record, Runnable ack, Delivery.EnvelopeSink sink) {
    if (delivery.ignore(record)) {
      ack.run();
      return;
    }
    bus.pause(topic);
    try {
      executor.execute(() -> finish(record, ack, sink));
    } catch (RejectedExecutionException ex) {
      bus.resume(topic);
      delivery.again(record);
      ack.run();
    }
  }

  private static void dead(BusRecord record, Runnable ack, Consumer<BusRecord> sink) {
    sink.accept(record);
    ack.run();
  }

  private void finish(BusRecord record, Runnable ack, Delivery.EnvelopeSink sink) {
    try {
      delivery.attempt(record, sink);
    } finally {
      ack.run();
      bus.resume(topic);
    }
  }

  private static ThreadFactory named() {
    AtomicInteger count = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "planner-solve-" + count.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
