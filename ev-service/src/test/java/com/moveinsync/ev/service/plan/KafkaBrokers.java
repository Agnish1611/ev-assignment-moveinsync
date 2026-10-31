package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.service.bus.TopicLayout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/** One broker with auto-create off and the fleet topics present. */
final class KafkaBrokers {

  private static KafkaContainer ready;

  private KafkaBrokers() {
    throw new IllegalStateException("utility");
  }

  static KafkaContainer bare() {
    return new KafkaContainer("apache/kafka-native:3.8.0")
        .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
  }

  static synchronized String ready() {
    if (ready == null) {
      ready = bare();
      ready.start();
      TopicLayout.ensure(ready.getBootstrapServers());
    }
    return ready.getBootstrapServers();
  }

  static synchronized void pause() {
    ready();
    DockerClientFactory.instance().client().pauseContainerCmd(ready.getContainerId()).exec();
  }

  static synchronized void resume() {
    if (ready == null) {
      return;
    }
    DockerClientFactory.instance().client().unpauseContainerCmd(ready.getContainerId()).exec();
  }
}
