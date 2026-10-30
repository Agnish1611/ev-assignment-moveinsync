package com.moveinsync.ev.service.bus;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/** One bus message. The value bytes are copied on the way in and on the way out. */
public final class BusRecord {

  private final String topic;
  private final String key;
  private final byte[] value;
  private final Map<String, String> headers;
  private final int partition;
  private final long offset;

  public BusRecord(
      String topic,
      String key,
      byte[] value,
      Map<String, String> headers,
      int partition,
      long offset) {
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be blank");
    }
    this.topic = topic;
    this.key = Objects.requireNonNull(key, "key must not be null");
    this.value = Objects.requireNonNull(value, "value must not be null").clone();
    this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers must not be null"));
    this.partition = partition;
    this.offset = offset;
  }

  public static BusRecord of(String topic, String key, byte[] value) {
    return new BusRecord(topic, key, value, Map.of(), -1, -1);
  }

  public String topic() {
    return topic;
  }

  public String key() {
    return key;
  }

  public byte[] value() {
    return value.clone();
  }

  public Map<String, String> headers() {
    return headers;
  }

  public int partition() {
    return partition;
  }

  public long offset() {
    return offset;
  }

  public BusRecord with(String nextTopic, Map<String, String> nextHeaders) {
    return new BusRecord(nextTopic, key, value.clone(), nextHeaders, -1, -1);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BusRecord record)) {
      return false;
    }
    return partition == record.partition
        && offset == record.offset
        && topic.equals(record.topic)
        && key.equals(record.key)
        && headers.equals(record.headers)
        && Arrays.equals(value, record.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(topic, key, headers, partition, offset, Arrays.hashCode(value));
  }
}
