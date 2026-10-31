package com.moveinsync.ev.service.api;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.cache.CacheInvalidator;
import com.moveinsync.ev.service.cache.CacheSettings;
import com.moveinsync.ev.service.cache.PlanCaches;
import com.moveinsync.ev.service.cache.PlanReadCache;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.observe.ServiceMeters;
import com.moveinsync.ev.service.plan.JobReaper;
import com.moveinsync.ev.service.plan.JobSolve;
import com.moveinsync.ev.service.plan.PlanPublication;
import com.moveinsync.ev.service.plan.SchemaMigrator;
import com.moveinsync.ev.service.repository.CatalogRepository;
import com.moveinsync.ev.service.repository.IdempotencyRepository;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.service.repository.PlanViewRepository;
import com.moveinsync.ev.validator.PlanValidator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Wires the HTTP API. A process without a web server does not create these beans. */
@Configuration
@ConditionalOnWebApplication
@EnableConfigurationProperties({
  ApiSettings.class,
  CacheSettings.class,
  RoleSettings.class,
  JobSettings.class
})
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
  @ConditionalOnExpression(ProcessRole.API)
  PlanJobs planJobs(
      IdempotentCalls calls,
      ObjectProvider<DataSource> sources,
      Clock clock,
      ApiSettings settings) {
    return new PlanJobs(calls, new AppConnections(sources), clock, settings.maxIdLength());
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.WORKER)
  JobClaims jobClaims(JobSettings settings) {
    return new JobClaims(new JobRepository(), settings);
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.WORKER)
  JobReaper jobReaper(ObjectProvider<DataSource> sources, Clock clock, JobSettings settings) {
    return new JobReaper(sources.getObject(), new JobRepository(), clock, settings);
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.WORKER)
  PlanPublication planPublication(Clock clock, ServiceMeters meters) {
    return new PlanPublication(
        new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock, meters);
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.WORKER)
  JobSolve jobSolve(
      ObjectProvider<DataSource> sources,
      Clock clock,
      JobSettings settings,
      PlanPublication publication,
      ServiceMeters meters) {
    DataSource dataSource = sources.getObject();
    JobRepository jobs = new JobRepository();
    return new JobSolve(
        dataSource,
        jobs,
        new JobClaims(jobs, settings),
        new FleetLoad(dataSource),
        publication,
        new PlanReadRepository(),
        new PlanHeadRepository(),
        clock,
        BusSettings.standard().longestSolveMillis(),
        meters);
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.API)
  CatalogService catalogService(
      IdempotentCalls calls, ApiSettings settings, MessageBus bus, Clock clock) {
    return new CatalogService(calls, new CatalogRepository(), settings, bus, clock);
  }

  @Bean
  @ConditionalOnExpression(ProcessRole.API)
  IngressService ingressService(
      IdempotentCalls calls, MessageBus bus, Clock clock, ApiSettings settings) {
    return new IngressService(calls, bus, clock, settings.maxIdLength());
  }
}
