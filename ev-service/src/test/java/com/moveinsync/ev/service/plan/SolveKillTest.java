package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.api.JobClaims;
import com.moveinsync.ev.service.api.JobSettings;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.Delivery;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.PlannerWorker;
import com.moveinsync.ev.service.bus.SeenSequences;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.observe.DeliveryObserver;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Closing the worker during the solve leaves the head. The replacement publishes it once. */
class SolveKillTest {

  private static final String TENANT = "tenant-solve-kill";
  private static final String SHARD = "shard-solve-kill";
  private static final Instant START = ValidFleet.START;

  @Test
  void aKilledSolveIsFinishedByTheReplacement() throws Exception {
    DataSource database = PlanDatabase.open();
    PlannerInput fleet = ValidFleet.input(TenantId.of(TENANT));
    ScenarioCatalog.store(database, fleet, SHARD);
    storeJob(database);
    MovingClock clock = new MovingClock(START);
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, BusSettings.standard());
    CountDownLatch entered = new CountDownLatch(1);
    PlannerWorker killed = new PlannerWorker(bus, delivery, BusSettings.standard());
    killed.start(solver(blockFirst(database, entered), clock)::accept);
    bus.send(trigger());
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    killed.close();
    untilQueued(bus);
    assertThat(head()).isZero();

    PlannerWorker replacement = new PlannerWorker(bus, delivery, BusSettings.standard());
    replacement.start(solver(database, clock)::accept);
    clock.advance(Duration.ofSeconds(5));
    bus.releaseDue(clock.instant());
    untilHead();
    assertThat(head()).isEqualTo(1L);
    assertThat(versions()).isEqualTo(1L);
    replacement.close();
  }

  private static void untilQueued(MemoryMessageBus bus) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (bus.queued() == 0L) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("retry was not deferred");
      }
      Thread.onSpinWait();
    }
  }

  private static void untilHead() {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (head() < 1L) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("replacement did not publish");
      }
      Thread.onSpinWait();
    }
  }

  private static JobSolve solver(DataSource source, Clock clock) {
    JobRepository jobs = new JobRepository();
    return new JobSolve(
        source,
        jobs,
        new JobClaims(jobs, new JobSettings(120, 5_000)),
        new FleetLoad(source),
        new PlanPublication(
            new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock),
        new PlanReadRepository(),
        new PlanHeadRepository(),
        clock,
        BusSettings.standard().longestSolveMillis(),
        DeliveryObserver.none());
  }

  private static void storeJob(DataSource database) throws SQLException {
    try (Connection connection = database.getConnection()) {
      connection.setAutoCommit(false);
      new JobRepository().store(connection, TENANT, "job-kill", SHARD, START, "INITIAL");
      connection.commit();
    }
  }

  private static BusRecord trigger() {
    Envelope envelope =
        new Envelope(
            "job-kill",
            TENANT,
            "plan-api",
            1L,
            START,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanRequested(SHARD, "job-kill", Envelope.PlanRequested.INITIAL));
    return BusRecord.of(Topics.PLAN_TRIGGERS, TENANT + ":" + SHARD, EnvelopeCodec.write(envelope));
  }

  private static DataSource blockFirst(DataSource delegate, CountDownLatch entered) {
    AtomicBoolean once = new AtomicBoolean(true);
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())
                  && (args == null || args.length == 0)
                  && once.compareAndSet(true, false)) {
                entered.countDown();
                waitForKill();
              }
              return invoke(method, delegate, args);
            });
  }

  private static void waitForKill() throws SQLException {
    try {
      if (!new CountDownLatch(1).await(5, TimeUnit.SECONDS)) {
        throw new SQLException("solve was not killed");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SQLException("killed");
    }
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
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?", TENANT, SHARD);
  }

  private static long versions() {
    return PlanDatabase.count(
        "SELECT count(*) FROM plan_version WHERE tenant_id = ? AND shard = ?", TENANT, SHARD);
  }

  private static final class MovingClock extends Clock {
    private Instant current;

    private MovingClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public Instant instant() {
      return current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
