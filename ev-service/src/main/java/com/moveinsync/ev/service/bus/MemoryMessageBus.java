package com.moveinsync.ev.service.bus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process bus. Sends run on the caller thread. Deferred records wait for {@link #releaseDue}.
 */
public final class MemoryMessageBus implements MessageBus {

  private final Map<String, RecordHandler> handlers = new HashMap<>();
  private final Map<String, List<BusRecord>> held = new HashMap<>();
  private final List<Deferred> deferred = new ArrayList<>();
  private final List<String> paused = new ArrayList<>();

  @Override
  public synchronized void send(BusRecord record) {
    if (paused.contains(record.topic())) {
      held.computeIfAbsent(record.topic(), key -> new ArrayList<>()).add(record);
      return;
    }
    deliver(record);
  }

  @Override
  public synchronized void subscribe(String topic, RecordHandler handler) {
    handlers.put(topic, handler);
  }

  @Override
  public synchronized void pause(String topic) {
    if (!paused.contains(topic)) {
      paused.add(topic);
    }
  }

  @Override
  public synchronized void resume(String topic) {
    paused.remove(topic);
    List<BusRecord> waiting = held.remove(topic);
    if (waiting == null) {
      return;
    }
    for (BusRecord record : List.copyOf(waiting)) {
      if (paused.contains(topic)) {
        held.computeIfAbsent(topic, key -> new ArrayList<>()).add(record);
      } else {
        deliver(record);
      }
    }
  }

  @Override
  public synchronized void defer(BusRecord record, Instant until) {
    deferred.add(new Deferred(record, until));
  }

  @Override
  public synchronized void releaseDue(Instant now) {
    List<BusRecord> due = new ArrayList<>();
    deferred.removeIf(
        item -> {
          if (now.isBefore(item.until())) {
            return false;
          }
          due.add(item.record());
          return true;
        });
    for (BusRecord record : due) {
      send(record);
    }
  }

  @Override
  public void close() {
    // Nothing to release. The records live only in this object.
  }

  private void deliver(BusRecord record) {
    RecordHandler handler = handlers.get(record.topic());
    if (handler != null) {
      handler.onRecord(record, () -> {});
    }
  }

  private record Deferred(BusRecord record, Instant until) {}
}
