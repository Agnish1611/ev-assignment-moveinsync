package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** A commit that dies rolls back. The next attempt publishes one new version. */
class CommitKillTest {

  private static final TenantId TENANT = TenantId.of("tenant-commit-kill");
  private static final String SHARD = "shard-commit-kill";

  @Test
  void aKilledCommitLeavesThePreviousHead() {
    DataSource database = PlanDatabase.open();
    PlannerInput fleet = ValidFleet.input(TENANT);
    FleetPlan plan = ValidFleet.plan(fleet);
    ValidationInput ground = ValidFleet.groundTruth(fleet);
    Clock clock = Clock.fixed(ValidFleet.START, ZoneOffset.UTC);
    PlanPublication publication = publication(clock);

    assertThat(publication.publish(database, command(plan, ground, 0L, 1L)).outcome())
        .isEqualTo(PublishOutcome.PUBLISHED);
    assertThatThrownBy(() -> publication.publish(killing(database), command(plan, ground, 1L, 2L)))
        .isInstanceOf(PlanStoreException.class)
        .hasMessage("plan publication failed");
    assertThat(versions()).isEqualTo(1L);
    assertThat(publication.publish(database, command(plan, ground, 1L, 3L)).outcome())
        .isEqualTo(PublishOutcome.PUBLISHED);
    assertThat(head()).isEqualTo(2L);
    assertThat(versions()).isEqualTo(2L);
  }

  private static PlanPublication publication(Clock clock) {
    return new PlanPublication(
        new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock);
  }

  private static PublishCommand command(
      FleetPlan plan, ValidationInput ground, long expected, long sequence) {
    return new PublishCommand(
        TENANT,
        SHARD,
        expected,
        plan,
        ground,
        "commit-kill",
        sequence,
        0,
        0,
        0L,
        0L,
        "INITIAL",
        0L,
        0L);
  }

  private static DataSource killing(DataSource delegate) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object value = invoke(method, delegate, args);
              if ("getConnection".equals(method.getName()) && value instanceof Connection raw) {
                return killing(raw);
              }
              return value;
            });
  }

  private static Connection killing(Connection raw) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("commit".equals(method.getName())) {
                raw.rollback();
                throw new SQLException("commit failed");
              }
              return invoke(method, raw, args);
            });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException ex) {
      throw ex.getCause();
    }
  }

  private static long head() {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?", TENANT.value(), SHARD);
  }

  private static long versions() {
    return PlanDatabase.count(
        "SELECT count(*) FROM plan_version WHERE tenant_id = ? AND shard = ?",
        TENANT.value(),
        SHARD);
  }
}
