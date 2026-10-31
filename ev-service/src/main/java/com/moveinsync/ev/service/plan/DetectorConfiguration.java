package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.service.api.ProcessRole;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.observe.ServiceMeters;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Starts the telemetry and charger-status consumer. The web role does not detect drift. */
@Configuration
@ConditionalOnWebApplication
@ConditionalOnExpression(ProcessRole.DETECTOR)
public class DetectorConfiguration {

  @Bean
  FleetDetector fleetDetector(
      DataSource dataSource, MessageBus bus, Clock clock, ServiceMeters meters) {
    FleetDetector detector = new FleetDetector(dataSource, bus, clock, meters);
    detector.start();
    return detector;
  }
}
