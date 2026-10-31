package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.service.api.ProcessRole;
import com.moveinsync.ev.service.fault.ResilienceSettings;
import com.moveinsync.ev.service.repository.OutboxRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Kafka bus for a web process. Tests that leave the bootstrap unset keep the in-memory bus. */
@Configuration
@EnableScheduling
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ev.bus.bootstrap")
public class BusConfiguration {

  @Bean(destroyMethod = "close")
  MessageBus messageBus(
      @Value("${ev.bus.bootstrap}") String bootstrap,
      @Value("${ev.bus.group}") String group,
      @Value("${ev.schema.registry:}") String registry,
      Clock clock,
      ResilienceSettings resilience) {
    if (group.isBlank()) {
      throw new IllegalStateException("ev.bus.group is required");
    }
    TopicLayout.require(bootstrap);
    KafkaMessageBus bus =
        new KafkaMessageBus(bootstrap, group, BusSettings.standard(), clock, resilience);
    bus.start();
    if (!registry.isBlank()) {
      new SchemaPublisher().publish(registry);
    }
    return bus;
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.WORKER)
  OutboxPump outboxPump(ObjectProvider<DataSource> sources, MessageBus bus) {
    return new OutboxPump(
        sources, new OutboxRelay(bus, new OutboxRepository(), BusSettings.standard()));
  }
}
