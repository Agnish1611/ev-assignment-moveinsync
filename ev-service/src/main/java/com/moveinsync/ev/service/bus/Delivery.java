package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.service.observe.DeliveryObserver;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * At-least-once handling. A repeated sequence is ignored, an older sequence is dropped, and a
 * handler failure is retried once after a short delay, once after a long delay, then dead-lettered.
 */
public final class Delivery {

  static final String ATTEMPT = "attempt";
  static final String NOT_BEFORE = "not-before";
  static final String ORIGIN = "origin";

  private final MessageBus bus;
  private final SeenSequences seen;
  private final Clock clock;
  private final BusSettings settings;
  private final DeliveryObserver observer;

  public Delivery(MessageBus bus, SeenSequences seen, Clock clock, BusSettings settings) {
    this(bus, seen, clock, settings, DeliveryObserver.none());
  }

  public Delivery(
      MessageBus bus,
      SeenSequences seen,
      Clock clock,
      BusSettings settings,
      DeliveryObserver observer) {
    this.bus = bus;
    this.seen = seen;
    this.clock = clock;
    this.settings = settings;
    this.observer = Objects.requireNonNull(observer, "observer must not be null");
  }

  public void install(String topic) {
    bus.subscribe(Topics.retryShort(topic), this::onRetry);
    bus.subscribe(Topics.retryLong(topic), this::onRetry);
  }

  public boolean ignore(BusRecord record) {
    try {
      Envelope envelope = EnvelopeCodec.read(record.value());
      return seen.preview(envelope.tenant(), envelope.producer(), envelope.sequence())
          != SeenSequences.Disposition.ACCEPT;
    } catch (RuntimeException ex) {
      return false;
    }
  }

  public void attempt(BusRecord record, EnvelopeSink sink) {
    Envelope envelope;
    try {
      envelope = EnvelopeCodec.read(record.value());
    } catch (RuntimeException ex) {
      fail(record);
      return;
    }
    SeenSequences.Disposition disposition =
        seen.preview(envelope.tenant(), envelope.producer(), envelope.sequence());
    if (disposition == SeenSequences.Disposition.DUPLICATE) {
      observer.duplicate();
      return;
    }
    if (disposition == SeenSequences.Disposition.LATE) {
      observer.late();
      return;
    }
    try {
      sink.accept(envelope);
      seen.commit(envelope.tenant(), envelope.producer(), envelope.sequence());
    } catch (RuntimeException ex) {
      fail(record);
    }
  }

  private void onRetry(BusRecord record, Runnable ack) {
    Instant due = due(record);
    if (clock.instant().isBefore(due)) {
      bus.defer(record, due);
      return;
    }
    String origin = record.headers().getOrDefault(ORIGIN, record.topic());
    bus.send(record.with(origin, record.headers()));
    ack.run();
  }

  private void fail(BusRecord record) {
    int attempt = attempt(record);
    String origin = record.headers().getOrDefault(ORIGIN, record.topic());
    if (attempt >= 2) {
      observer.deadLettered();
      bus.send(
          record.with(
              Topics.deadLetter(origin), headers(record, attempt, origin, clock.instant())));
      return;
    }
    observer.retried();
    long delay = attempt == 0 ? settings.retryShortSeconds() : settings.retryLongSeconds();
    Instant due = clock.instant().plusSeconds(delay);
    String topic = attempt == 0 ? Topics.retryShort(origin) : Topics.retryLong(origin);
    bus.send(record.with(topic, headers(record, attempt + 1, origin, due)));
  }

  private static Map<String, String> headers(
      BusRecord record, int attempt, String origin, Instant due) {
    Map<String, String> headers = new HashMap<>(record.headers());
    headers.put(ATTEMPT, Integer.toString(attempt));
    headers.put(NOT_BEFORE, Long.toString(due.getEpochSecond()));
    headers.put(ORIGIN, origin);
    return headers;
  }

  private static int attempt(BusRecord record) {
    String raw = record.headers().get(ATTEMPT);
    if (raw == null) {
      return 0;
    }
    return Integer.parseInt(raw);
  }

  private static Instant due(BusRecord record) {
    String raw = record.headers().get(NOT_BEFORE);
    if (raw == null) {
      return Instant.EPOCH;
    }
    return Instant.ofEpochSecond(Long.parseLong(raw));
  }

  @FunctionalInterface
  public interface EnvelopeSink {
    void accept(Envelope envelope);
  }
}
