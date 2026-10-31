package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.service.fault.CallBlocked;
import com.moveinsync.ev.service.fault.CallGate;
import com.moveinsync.ev.service.fault.ResilienceSettings;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Kafka transport. The poll thread never runs a solve. {@code max.poll.interval.ms} is longer than
 * the longest solve, and a paused topic stays paused across a rebalance.
 */
public final class KafkaMessageBus implements MessageBus {

  private final KafkaProducer<String, byte[]> producer;
  private final KafkaPoller poller;
  private final BusSettings settings;
  private final CallGate gate;
  private final long sendWaitMillis;

  public KafkaMessageBus(String bootstrap, String groupId, BusSettings settings, Clock clock) {
    this(bootstrap, groupId, settings, clock, ResilienceSettings.standard());
  }

  public KafkaMessageBus(
      String bootstrap,
      String groupId,
      BusSettings settings,
      Clock clock,
      ResilienceSettings resilience) {
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(resilience, "resilience must not be null");
    this.gate = new CallGate(resilience.failureLimit(), resilience.openMillis(), clock);
    this.sendWaitMillis = resilience.deliveryTimeoutMillis();
    this.producer = KafkaClients.producer(bootstrap, resilience);
    this.poller =
        new KafkaPoller(
            KafkaClients.consumer(bootstrap, groupId, settings, resilience), settings, clock);
  }

  public void start() {
    poller.start();
  }

  public Optional<RuntimeException> failure() {
    return poller.failure();
  }

  @Override
  public void send(BusRecord record) {
    try {
      gate.enter();
    } catch (CallBlocked ex) {
      throw new BusException("send failed", ex);
    }
    ProducerRecord<String, byte[]> produced =
        new ProducerRecord<>(record.topic(), record.key(), record.value());
    for (Map.Entry<String, String> header : record.headers().entrySet()) {
      produced.headers().add(header.getKey(), header.getValue().getBytes(StandardCharsets.UTF_8));
    }
    try {
      producer.send(produced).get(sendWaitMillis, TimeUnit.MILLISECONDS);
      gate.success();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      gate.failure();
      throw new BusException("send interrupted", ex);
    } catch (ExecutionException | TimeoutException ex) {
      gate.failure();
      throw new BusException("send failed", ex);
    }
  }

  @Override
  public void subscribe(String topic, RecordHandler handler) {
    poller.subscribe(topic, handler);
  }

  @Override
  public void pause(String topic) {
    poller.pause(topic);
  }

  @Override
  public void resume(String topic) {
    poller.resume(topic);
  }

  @Override
  public void defer(BusRecord record, Instant until) {
    poller.defer(record.topic(), until);
  }

  @Override
  public void releaseDue(Instant now) {
    poller.releaseDue(now);
  }

  @Override
  public long consumerLag() {
    return poller.recordsLag();
  }

  @Override
  public void close() {
    poller.close();
    producer.close(Duration.ofMillis(settings.sendTimeoutMillis()));
  }
}
