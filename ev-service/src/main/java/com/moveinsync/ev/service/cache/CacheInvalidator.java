package com.moveinsync.ev.service.cache;

import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.observe.OperationalMeters;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drops cached heads and fleet rows when the bus says they changed. This process owns those two
 * topics. A poison body is counted and is not logged.
 */
public final class CacheInvalidator {

  private static final Logger log = LoggerFactory.getLogger(CacheInvalidator.class);

  private final MessageBus bus;
  private final PlanReadCache cache;
  private final OperationalMeters meters;

  public CacheInvalidator(MessageBus bus, PlanReadCache cache, OperationalMeters meters) {
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  public void start() {
    bus.subscribe(Topics.PLAN_EVENTS, this::onRecord);
    bus.subscribe(Topics.CONFIG, this::onRecord);
  }

  private void onRecord(BusRecord record, Runnable ack) {
    try {
      apply(EnvelopeCodec.read(record.value()));
    } catch (IllegalArgumentException ex) {
      log.warn("rejected bus event {}", ex.getClass().getSimpleName());
      meters.rejectedEvent();
    }
    ack.run();
  }

  private void apply(Envelope envelope) {
    if (envelope.payload() instanceof Envelope.PlanPublished published) {
      cache.evictCurrent(envelope.tenant(), published.shard());
      return;
    }
    if (envelope.payload() instanceof Envelope.ConfigChanged) {
      cache.evictFleet(envelope.tenant());
    }
  }
}
