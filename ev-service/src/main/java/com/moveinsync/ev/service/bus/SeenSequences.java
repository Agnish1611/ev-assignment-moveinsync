package com.moveinsync.ev.service.bus;

import java.util.HashMap;
import java.util.Map;

/**
 * Highest committed sequence per producer. A lower sequence is late. The same sequence is a replay.
 */
final class SeenSequences {

  enum Disposition {
    ACCEPT,
    DUPLICATE,
    LATE
  }

  private final Map<String, Long> highest = new HashMap<>();

  synchronized Disposition preview(String tenant, String producer, long sequence) {
    long seen = highest.getOrDefault(key(tenant, producer), -1L);
    if (sequence < seen) {
      return Disposition.LATE;
    }
    if (sequence == seen) {
      return Disposition.DUPLICATE;
    }
    return Disposition.ACCEPT;
  }

  synchronized void commit(String tenant, String producer, long sequence) {
    String key = key(tenant, producer);
    long seen = highest.getOrDefault(key, -1L);
    if (sequence > seen) {
      highest.put(key, sequence);
    }
  }

  private static String key(String tenant, String producer) {
    return tenant + "\n" + producer;
  }
}
