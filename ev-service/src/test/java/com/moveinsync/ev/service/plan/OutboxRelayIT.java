package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.KafkaMessageBus;
import com.moveinsync.ev.service.bus.OutboxRelay;
import com.moveinsync.ev.service.bus.PlanProjection;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.OutboxRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** A published plan leaves the outbox once. Another tenant cannot see the row. */
class OutboxRelayIT {

  private static final String SHARD = "shard-relay";

  @Test
  void aPublishedPlanIsDeliveredOnce() throws Exception {
    DataSource dataSource = PlanDatabase.open();
    publish(dataSource);
    assertThat(countAs("ev_app", "tenant-b")).isZero();
    assertThat(countAs("ev_app", "")).isZero();
    assertThat(countWithoutTenant()).isZero();
    assertThat(countAs("ev_relay", "")).isPositive();

    BusSettings settings =
        new BusSettings(2, 8, 120_000L, 30_000L, 5L, 60L, 200L, 10_000L, 100, 10);
    KafkaMessageBus bus =
        new KafkaMessageBus(KafkaBrokers.ready(), "relay-it", settings, Clock.systemUTC());
    PlanProjection projection = new PlanProjection();
    CountDownLatch seen = new CountDownLatch(1);
    bus.subscribe(
        Topics.PLAN_EVENTS,
        (BusRecord record, Runnable ack) -> {
          projection.observe(EnvelopeCodec.read(record.value()));
          if (projection.version(SHARD) == 1L) {
            seen.countDown();
          }
          ack.run();
        });
    bus.start();
    try {
      OutboxRelay relay = new OutboxRelay(bus, new OutboxRepository(), settings);
      assertThat(relay.publishPending(dataSource)).isPositive();
      assertThat(seen.await(60, TimeUnit.SECONDS)).isTrue();
      assertThat(projection.version(SHARD)).isEqualTo(1L);
      assertThat(PlanDatabase.count("SELECT count(*) FROM outbox WHERE shard = ?", SHARD)).isZero();
      assertThat(relay.publishPending(dataSource)).isZero();
      assertThat(bus.failure()).isEmpty();
    } finally {
      bus.close();
    }
  }

  private static void publish(DataSource dataSource) {
    PlannerInput fleet = ValidFleet.input();
    FleetPlan plan = ValidFleet.plan(fleet);
    ValidationInput groundTruth = ValidFleet.groundTruth(fleet);
    PublishCommand command =
        new PublishCommand(
            ValidFleet.TENANT,
            SHARD,
            0L,
            plan,
            groundTruth,
            "relay-producer",
            1L,
            1,
            0,
            5L,
            0L,
            "PUBLISHED",
            0L,
            0L);
    PublishResult result =
        new PlanPublication(
                new PlanValidator(),
                new PlanHeadRepository(),
                new PlanReadRepository(),
                Clock.fixed(ValidFleet.START, ZoneOffset.UTC))
            .publish(dataSource, command);
    assertThat(result.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
  }

  private static long countWithoutTenant() {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL ROLE ev_app");
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM outbox")) {
          if (!rows.next()) {
            return 0L;
          }
          return rows.getLong(1);
        }
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("outbox count failed", ex);
    }
  }

  private static long countAs(String role, String tenant) {
    if (!role.equals("ev_app") && !role.equals("ev_relay")) {
      throw new IllegalArgumentException("role is not a database role");
    }
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL ROLE " + role);
      }
      try (PreparedStatement statement =
          connection.prepareStatement("SELECT set_config('app.tenant', ?, true)")) {
        statement.setString(1, tenant);
        try (ResultSet rows = statement.executeQuery()) {
          if (!rows.next()) {
            throw new IllegalStateException("app.tenant was not set");
          }
        }
      }
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count(*) FROM outbox")) {
        if (!rows.next()) {
          return 0L;
        }
        return rows.getLong(1);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("outbox count failed", ex);
    }
  }
}
