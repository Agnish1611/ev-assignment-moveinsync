package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.fault.CallBlocked;
import com.moveinsync.ev.service.fault.CallGate;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/** Readiness for the database. The body never includes the driver message. */
final class DatabaseReadiness implements HealthIndicator {

  private final DataSource dataSource;
  private final CallGate gate;
  private final int timeoutSeconds;

  DatabaseReadiness(DataSource dataSource, CallGate gate, int timeoutSeconds) {
    this.dataSource = dataSource;
    this.gate = gate;
    this.timeoutSeconds = timeoutSeconds;
  }

  @Override
  public Health health() {
    try {
      gate.enter();
    } catch (CallBlocked ex) {
      return Health.down().build();
    }
    try (Connection connection = dataSource.getConnection()) {
      if (!connection.isValid(timeoutSeconds)) {
        gate.failure();
        return Health.down().build();
      }
      gate.success();
      return Health.up().build();
    } catch (SQLException ex) {
      gate.failure();
      return Health.down().build();
    }
  }
}
