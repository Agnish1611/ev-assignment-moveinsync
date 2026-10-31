package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.service.EvServiceApplication;
import com.moveinsync.ev.service.bus.TopicLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.kafka.KafkaContainer;

/** Topics exist before the process starts. A missing name stops it. Auto-create stays off. */
class TopicLayoutIT {

  @Test
  void topicsAreCreatedOnceAndAMissingNameIsRejected() throws Exception {
    String bootstrap = KafkaBrokers.ready();
    TopicLayout.ensure(bootstrap);
    assertThat(TopicLayout.names()).hasSize(20);
    for (String name : TopicLayout.names()) {
      assertThat(TopicLayout.partitions(bootstrap, name)).isEqualTo(6);
    }
    assertAbsent(bootstrap);
  }

  @Test
  void aMissingTopicStopsStartup() {
    PlanDatabase.open();
    try (KafkaContainer kafka = KafkaBrokers.bare()) {
      kafka.start();
      assertThatThrownBy(() -> TopicLayout.require(kafka.getBootstrapServers()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("missing topic ev.plan-triggers.v1");
      TokenFixture tokens = TokenFixture.start();
      try {
        assertThatThrownBy(() -> start(kafka.getBootstrapServers(), tokens))
            .hasStackTraceContaining("missing topic ev.plan-triggers.v1");
      } finally {
        tokens.stop();
      }
    }
  }

  @Test
  void composeCreatesTheSameTopicsBeforeTheApps() throws Exception {
    String compose = Files.readString(Path.of("..", "docker-compose.yml"));
    String script = Files.readString(Path.of("..", "deploy", "kafka", "create-topics.sh"));
    assertThat(compose).contains("KAFKA_AUTO_CREATE_TOPICS_ENABLE: \"false\"");
    assertThat(compose).contains("ev-worker:");
    assertThat(compose).contains("second-worker");
    assertThat(script).contains("--partitions 6");
    assertThat(script).contains("--replication-factor 1");
    for (String name : TopicLayout.names()) {
      assertThat(script).contains(name);
    }
  }

  private static void assertAbsent(String bootstrap) throws Exception {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
    props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "2000");
    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "4000");
    try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>("ev.absent.v1", "key", new byte[] {1});
      assertThatThrownBy(() -> producer.send(record).get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
    }
    assertThatThrownBy(() -> TopicLayout.partitions(bootstrap, "ev.absent.v1"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static void start(String bootstrap, TokenFixture tokens) {
    new SpringApplicationBuilder(EvServiceApplication.class)
        .properties(
            "server.port=0",
            "spring.datasource.url=" + PlanDatabase.jdbcUrl(),
            "spring.datasource.username=" + PlanDatabase.databaseUser(),
            "spring.datasource.password=" + PlanDatabase.databasePassword(),
            "spring.flyway.enabled=false",
            "ev.schema.migrate=false",
            "ev.roles=api",
            "ev.bus.bootstrap=" + bootstrap,
            "ev.bus.group=missing-topic",
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + tokens.jwkUri())
        .run()
        .close();
  }
}
