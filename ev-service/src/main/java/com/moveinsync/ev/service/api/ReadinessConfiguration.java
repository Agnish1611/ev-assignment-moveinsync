package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.fault.CallGate;
import com.moveinsync.ev.service.fault.ResilienceSettings;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Readiness for the database. The contributor exists even when this process has no web server, so
 * the readiness group can name it.
 */
@Configuration
@EnableConfigurationProperties(ResilienceSettings.class)
class ReadinessConfiguration {

  @Bean(name = "db")
  HealthIndicator databaseReadiness(
      ObjectProvider<DataSource> sources,
      ObjectProvider<CallGate> gates,
      ResilienceSettings resilience) {
    DataSource source = sources.getIfAvailable();
    CallGate gate = gates.getIfAvailable();
    if (source == null || gate == null) {
      return () -> Health.down().build();
    }
    return new DatabaseReadiness(source, gate, resilience.connectTimeoutSeconds());
  }
}
