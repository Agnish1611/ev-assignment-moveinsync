package com.moveinsync.ev.service.bus;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Metric;

/** Sums the consumer's records-lag gauges. The poll thread is the only caller. */
final class ConsumerLag {

  private ConsumerLag() {
    throw new IllegalStateException("utility");
  }

  static long total(KafkaConsumer<?, ?> consumer) {
    long sum = 0L;
    for (Metric metric : consumer.metrics().values()) {
      sum = add(sum, metric.metricName().name(), metric.metricValue());
    }
    return sum;
  }

  static long add(long sum, String name, Object value) {
    if (!"records-lag".equals(name) || !(value instanceof Number number)) {
      return sum;
    }
    long part = number.longValue();
    if (part <= 0L) {
      return sum;
    }
    return Math.addExact(sum, part);
  }
}
