package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The head stays on the last committed version when the next connection cannot be opened. */
class ConnectionDropIT {

  private static final String SHARD = "shard-drop";
  private static DataSource dataSource;
  private static FleetPlan plan;
  private static ValidationInput groundTruth;

  @BeforeAll
  static void open() {
    dataSource = PlanDatabase.open();
    PlannerInput fleet = ValidFleet.input();
    plan = ValidFleet.plan(fleet);
    groundTruth = ValidFleet.groundTruth(fleet);
  }

  @Test
  void aDroppedConnectionLeavesTheHeadOnTheCommittedVersion() {
    Clock clock = Clock.fixed(ValidFleet.START, ZoneOffset.UTC);
    PublishResult first = publish(dataSource, 0L, 1L, clock);

    assertThat(first.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
    assertThatThrownBy(() -> publish(new ClosedDatabase(), 1L, 2L, clock))
        .isInstanceOf(PlanStoreException.class)
        .hasMessage("plan publication failed")
        .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("db.internal"));
    assertThat(head()).isEqualTo(1L);
    assertThat(
            PlanDatabase.count(
                "SELECT count(*) FROM plan_version WHERE tenant_id = ? AND shard = ?",
                ValidFleet.TENANT.value(),
                SHARD))
        .isEqualTo(1L);
  }

  private static PublishResult publish(
      DataSource source, long expected, long sequence, Clock clock) {
    PublishCommand command =
        new PublishCommand(
            ValidFleet.TENANT,
            SHARD,
            expected,
            plan,
            groundTruth,
            "drop-writer",
            sequence,
            0,
            0,
            5L,
            0L,
            "PUBLISHED",
            0L,
            0L);
    return new PlanPublication(
            new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock)
        .publish(source, command);
  }

  private static long head() {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?",
        ValidFleet.TENANT.value(),
        SHARD);
  }

  private static final class ClosedDatabase implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      throw new SQLException("connection reset at db.internal");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new SQLException("connection reset at db.internal");
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
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
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException("no logger");
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
