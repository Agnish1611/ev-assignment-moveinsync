package com.moveinsync.ev.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.service.fault.CallGate;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** A database that refuses a connection takes readiness down and opens the gate. */
class DatabaseReadinessTest {

  @Test
  void aRefusedConnectionIsDownAndTheNextProbeDoesNotWait() {
    AtomicInteger attempts = new AtomicInteger();
    DataSource source = new RefusingSource(attempts);
    CallGate gate = new CallGate(1, 60_000L, Clock.systemUTC());
    DatabaseReadiness readiness = new DatabaseReadiness(source, gate, 1);

    assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(readiness.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(attempts).hasValue(1);
    assertThat(readiness.health().toString()).doesNotContain("db.internal");
  }

  private static final class RefusingSource implements DataSource {
    private final AtomicInteger attempts;

    private RefusingSource(AtomicInteger attempts) {
      this.attempts = attempts;
    }

    @Override
    public Connection getConnection() throws SQLException {
      attempts.incrementAndGet();
      throw new SQLException("connection reset at db.internal");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
      // The probe never logs through the driver.
    }

    @Override
    public void setLoginTimeout(int seconds) {
      // The probe does not wait on the driver.
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger()
        throws java.sql.SQLFeatureNotSupportedException {
      throw new java.sql.SQLFeatureNotSupportedException("no logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
