package com.moveinsync.ev.service.bus;

import java.time.Instant;

/**
 * Transport port. Kafka is one implementation. Callers pause a topic while a solve runs and defer a
 * retry until its deadline instead of sleeping.
 */
public interface MessageBus extends AutoCloseable {

  void send(BusRecord record);

  void subscribe(String topic, RecordHandler handler);

  void pause(String topic);

  void resume(String topic);

  void defer(BusRecord record, Instant until);

  void releaseDue(Instant now);

  /** Records waiting in this process. A Kafka bus reports zero here and lag separately. */
  default long queued() {
    return 0L;
  }

  /** Sum of Kafka records-lag. An in-memory bus reports zero. */
  default long consumerLag() {
    return 0L;
  }

  @Override
  void close();
}
