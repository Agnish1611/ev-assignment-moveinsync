package com.moveinsync.ev.service.bus;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Highest committed sequence per producer. A lower sequence is late. The same sequence is a replay.
 * The map drops the oldest producer once it passes the bound. A dropped producer can be accepted
 * again.
 */
final class SeenSequences {

  enum Disposition {
    ACCEPT,
    DUPLICATE,
    LATE
  }

  private final int maxProducers;
  private final Map<String, Long> highest = new LinkedHashMap<>();

  SeenSequences() {
    this(BusSettings.standard().seenProducers());
  }

  SeenSequences(int maxProducers) {
    if (maxProducers <= 0) {
      throw new IllegalArgumentException("seen producers must be positive");
    }
    this.maxProducers = maxProducers;
  }

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
    if (sequence <= seen) {
      return;
    }
    highest.remove(key);
    highest.put(key, sequence);
    trim();
  }

  private void trim() {
    Iterator<String> keys = highest.keySet().iterator();
    while (highest.size() > maxProducers && keys.hasNext()) {
      keys.next();
      keys.remove();
    }
  }

  private static String key(String tenant, String producer) {
    return tenant + "\n" + producer;
  }
}
