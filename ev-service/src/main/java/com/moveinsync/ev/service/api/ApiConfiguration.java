package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.repository.CatalogRepository;
import com.moveinsync.ev.service.repository.IdempotencyRepository;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanViewRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the HTTP API. A process without a web server does not create these beans. */
@Configuration
@ConditionalOnWebApplication
@EnableConfigurationProperties(ApiSettings.class)
public class ApiConfiguration {

  @Bean
  DataSource dataSource(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password) {
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(url);
    source.setUser(username);
    source.setPassword(password);
    return source;
  }

  @Bean
  @ConditionalOnMissingBean(MessageBus.class)
  MessageBus messageBus() {
    return new MemoryMessageBus();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  RateGate rateGate(ApiSettings settings) {
    return new RateGate(settings);
  }

  @Bean
  CallerResolver callerResolver(ApiSettings settings) {
    return new CallerResolver(settings.maxIdLength());
  }

  @Bean
  PlanQuery planQuery(ObjectProvider<DataSource> sources, ApiSettings settings) {
    return new PlanQuery(
        new AppConnections(sources), new PlanViewRepository(), settings.maxIdLength());
  }

  @Bean
  IdempotentCalls idempotentCalls(
      ObjectProvider<DataSource> sources, Clock clock, ApiSettings settings) {
    return new IdempotentCalls(
        new AppConnections(sources), new IdempotencyRepository(), clock, settings.maxIdLength());
  }

  @Bean
  PlanJobs planJobs(IdempotentCalls calls, Clock clock, ApiSettings settings) {
    return new PlanJobs(calls, new JobRepository(), clock, settings.maxIdLength());
  }

  @Bean
  CatalogService catalogService(IdempotentCalls calls, ApiSettings settings) {
    return new CatalogService(calls, new CatalogRepository(), settings);
  }

  @Bean
  IngressService ingressService(
      IdempotentCalls calls, MessageBus bus, Clock clock, ApiSettings settings) {
    return new IngressService(calls, bus, clock, settings.maxIdLength());
  }
}
