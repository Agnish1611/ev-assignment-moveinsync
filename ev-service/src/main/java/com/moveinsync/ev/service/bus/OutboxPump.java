package com.moveinsync.ev.service.bus;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/** Drains the outbox on a fixed delay. A failure is counted by the next attempt, not by a stack. */
public final class OutboxPump {

  private static final Logger log = LoggerFactory.getLogger(OutboxPump.class);

  private final ObjectProvider<DataSource> sources;
  private final OutboxRelay relay;

  public OutboxPump(ObjectProvider<DataSource> sources, OutboxRelay relay) {
    this.sources = sources;
    this.relay = relay;
  }

  @Scheduled(fixedDelayString = "${ev.bus.relay-millis}")
  public void publish() {
    try {
      relay.publishPending(sources.getObject());
    } catch (RuntimeException ex) {
      log.warn("outbox relay failed {}", ex.getClass().getSimpleName());
    }
  }
}
