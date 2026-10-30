package com.moveinsync.ev.service.api;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.cache.CacheInvalidator;
import com.moveinsync.ev.service.cache.CacheSettings;
import com.moveinsync.ev.service.cache.PlanCaches;
import com.moveinsync.ev.service.cache.PlanReadCache;
import com.moveinsync.ev.service.observe.ServiceMeters;
import com.moveinsync.ev.service.plan.SchemaMigrator;
import com.moveinsync.ev.service.repository.CatalogRepository;
import com.moveinsync.ev.service.repository.IdempotencyRepository;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanViewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the HTTP API. A process without a web server does not create these beans. */
@Configuration
@ConditionalOnWebApplication
@EnableConfigurationProperties({ApiSettings.class, CacheSettings.class})
public class ApiConfiguration {

  @Bean
  DataSource dataSource(Environment environment) {
    String url = environment.getRequiredProperty("spring.datasource.url");
    String username = environment.getRequiredProperty("spring.datasource.username");
    String password = environment.getRequiredProperty("spring.datasource.password");
    if (environment.getProperty("ev.schema.migrate", Boolean.class, Boolean.FALSE)) {
      SchemaMigrator.migrate(
          url,
          environment.getRequiredProperty("ev.schema.admin-user"),
          environment.getRequiredProperty("ev.schema.admin-password"));
    }
    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setURL(url);
    source.setUser(username);
    source.setPassword(password);
    return source;
  }

  @Bean
  @ConditionalOnProperty(name = "ev.bus.bootstrap", havingValue = "false", matchIfMissing = true)
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
  PlanReadCache planReadCache(CacheSettings settings) {
    return new PlanCaches(settings, Ticker.systemTicker());
  }

  @Bean
  ServiceMeters serviceMeters(MeterRegistry registry, Clock clock, MessageBus bus) {
    return new ServiceMeters(registry, clock, bus);
  }

  @Bean
  CacheInvalidator cacheInvalidator(MessageBus bus, PlanReadCache cache, ServiceMeters meters) {
    CacheInvalidator invalidator = new CacheInvalidator(bus, cache, meters);
    invalidator.start();
    return invalidator;
  }

  @Bean
  PlanQuery planQuery(
      ObjectProvider<DataSource> sources, ApiSettings settings, PlanReadCache cache) {
    return new PlanQuery(
        new AppConnections(sources), new PlanViewRepository(), cache, settings.maxIdLength());
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
  CatalogService catalogService(
      IdempotentCalls calls, ApiSettings settings, MessageBus bus, Clock clock) {
    return new CatalogService(calls, new CatalogRepository(), settings, bus, clock);
  }

  @Bean
  IngressService ingressService(
      IdempotentCalls calls, MessageBus bus, Clock clock, ApiSettings settings) {
    return new IngressService(calls, bus, clock, settings.maxIdLength());
  }
}
