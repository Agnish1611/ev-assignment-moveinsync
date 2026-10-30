package com.moveinsync.ev.service.bus;

import java.util.HashMap;
import java.util.Map;

/** Highest published version seen for a shard. An older event does not move it backward. */
public final class PlanProjection {

  private final Map<String, Long> versions = new HashMap<>();

  public void start(MessageBus bus) {
    bus.subscribe(Topics.PLAN_EVENTS, this::onRecord);
  }

  public synchronized void observe(Envelope envelope) {
    if (envelope.payload() instanceof Envelope.PlanPublished published
        && published.version() > versions.getOrDefault(published.shard(), 0L)) {
      versions.put(published.shard(), published.version());
    }
  }

  public synchronized long version(String shard) {
    return versions.getOrDefault(shard, 0L);
  }

  private void onRecord(BusRecord record, Runnable ack) {
    observe(EnvelopeCodec.read(record.value()));
    ack.run();
  }
}
