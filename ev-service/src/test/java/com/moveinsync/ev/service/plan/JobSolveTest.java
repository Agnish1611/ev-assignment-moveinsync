package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
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
import com.moveinsync.ev.service.fleet.FleetRejected;
import com.moveinsync.ev.service.fleet.FleetSource;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.service.observe.DeliveryObserver;
import com.moveinsync.ev.service.observe.PublishObserver;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A plan request publishes a validator-clean head. A duplicate or an older trigger does not. */
class JobSolveTest {

  private static final String TENANT = "tenant-solve";
  private static final PlannerInput FLEET = ValidFleet.input(TenantId.of(TENANT));
  private static final Instant AT = ValidFleet.START;
  private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
  private static DataSource database;

  @BeforeAll
  static void open() {
    database = PlanDatabase.open();
  }

  @Test
  void anInitialPlanPublishesAValidatorCleanHead() throws SQLException {
    String shard = "shard-solve-initial";
    ScenarioCatalog.store(database, FLEET, shard);
    JobSolve solve = solver(new FleetLoad(database));
    solve.accept(stored("job-initial", shard, 11L, Envelope.PlanRequested.INITIAL));

    assertThat(status("job-initial")).isEqualTo("PUBLISHED");
    assertThat(head(shard)).isEqualTo(1L);
    FleetPlan plan = PlanDocuments.gunzip(snapshot(shard, 1L));
    LoadedFleet fresh = new FleetLoad(database).load(TENANT, shard);
    assertThat(new PlanValidator().validate(fresh.validation(), plan).isValid()).isTrue();
  }

  @Test
  void aDuplicateAndAnOlderTriggerLeaveTheHead() throws SQLException {
    String shard = "shard-solve-order";
    ScenarioCatalog.store(database, FLEET, shard);
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = new Delivery(bus, new SeenSequences(), CLOCK, BusSettings.standard());
    PlannerWorker worker = new PlannerWorker(bus, delivery, BusSettings.standard());
    worker.start(solver(new FleetLoad(database))::accept);
    worker.watchDeadLetters(solver(new FleetLoad(database))::dead);

    Envelope newer = stored("job-order-new", shard, 22L, Envelope.PlanRequested.INITIAL);
    bus.send(record(newer));
    until("job-order-new", "PUBLISHED");
    bus.send(record(stored("job-order-old", shard, 21L, Envelope.PlanRequested.INITIAL)));
    bus.send(record(newer));

    assertThat(head(shard)).isEqualTo(1L);
    assertThat(status("job-order-old")).isEqualTo("ACCEPTED");
    assertThat(status("job-order-new")).isEqualTo("PUBLISHED");
    worker.close();
  }

  @Test
  void aFullReplanPublishesTheNextVersion() throws SQLException {
    String shard = "shard-solve-replan";
    ScenarioCatalog.store(database, FLEET, shard);
    JobSolve solve = solver(new FleetLoad(database));
    solve.accept(stored("job-replan-1", shard, 31L, Envelope.PlanRequested.INITIAL));
    solve.accept(stored("job-replan-2", shard, 32L, Envelope.PlanRequested.FULL_REPLAN));
    solve.accept(stored("job-replan-3", shard, 33L, Envelope.PlanRequested.INITIAL));

    assertThat(head(shard)).isEqualTo(2L);
    assertThat(status("job-replan-2")).isEqualTo("PUBLISHED");
    assertThat(status("job-replan-3")).isEqualTo("FAILED");
    assertThat(code("job-replan-3")).isEqualTo("CONFLICT");
  }

  @Test
  void aMovingCatalogMarksTheJobStale() throws SQLException {
    AtomicInteger reads = new AtomicInteger();
    FleetSource moving =
        (tenant, shard) ->
            new LoadedFleet(FLEET, ValidFleet.groundTruth(FLEET), reads.incrementAndGet());
    ensureTenant();
    JobSolve solve = solver(moving);
    solve.accept(stored("job-stale", "shard-solve-stale", 41L, Envelope.PlanRequested.INITIAL));

    assertThat(status("job-stale")).isEqualTo("STALE_INPUT");
    assertThat(code("job-stale")).isEqualTo(JobSolve.MOVED);
    assertThat(head("shard-solve-stale")).isZero();
    assertThat(reads).hasValue(3);
  }

  @Test
  void aMissingShardIsInfeasible() throws SQLException {
    ensureTenant();
    JobSolve solve = solver(new FleetLoad(database));
    solve.accept(stored("job-missing", "shard-solve-missing", 51L, Envelope.PlanRequested.INITIAL));

    assertThat(status("job-missing")).isEqualTo("INFEASIBLE");
    assertThat(code("job-missing")).isEqualTo("SHARD_MISSING");
    assertThat(head("shard-solve-missing")).isZero();
  }

  @Test
  void aDeadLetterFailsTheJob() throws SQLException {
    ensureTenant();
    JobSolve solve = solver(new FleetLoad(database));
    Envelope envelope = stored("job-dead", "shard-solve-dead", 61L, Envelope.PlanRequested.INITIAL);
    solve.dead(record(envelope));

    assertThat(status("job-dead")).isEqualTo("FAILED");
    assertThat(code("job-dead")).isEqualTo(JobSolve.DEAD);
  }

  @Test
  void aStoreFailureReachesTheDeadLetter() throws Exception {
    ensureTenant();
    MovingClock clock = new MovingClock(AT);
    MemoryMessageBus bus = new MemoryMessageBus();
    Delivery delivery = new Delivery(bus, new SeenSequences(), clock, BusSettings.standard());
    FleetSource failing =
        (tenant, shard) -> {
          throw new FleetRejected(JobSolve.STORE_FAILED, shard);
        };
    JobSolve solve = solver(failing, clock);
    PlannerWorker worker = new PlannerWorker(bus, delivery, BusSettings.standard());
    worker.start(solve::accept);
    worker.watchDeadLetters(solve::dead);

    bus.send(record(stored("job-store", "shard-solve-store", 71L, Envelope.PlanRequested.INITIAL)));
    untilQueued(bus);
    clock.advance(Duration.ofSeconds(5));
    bus.releaseDue(clock.instant());
    untilQueued(bus);
    clock.advance(Duration.ofSeconds(60));
    bus.releaseDue(clock.instant());
    until("job-store", "FAILED");

    assertThat(status("job-store")).isEqualTo("FAILED");
    assertThat(code("job-store")).isEqualTo(JobSolve.DEAD);
    worker.close();
  }

  private static JobSolve solver(FleetSource fleets) {
    return solver(fleets, CLOCK);
  }

  private static JobSolve solver(FleetSource fleets, Clock clock) {
    JobRepository jobs = new JobRepository();
    return new JobSolve(
        database,
        jobs,
        new JobClaims(jobs, new JobSettings(120, 5_000)),
        fleets,
        new PlanPublication(
            new PlanValidator(),
            new PlanHeadRepository(),
            new PlanReadRepository(),
            clock,
            PublishObserver.none()),
        new PlanReadRepository(),
        new PlanHeadRepository(),
        clock,
        BusSettings.standard().longestSolveMillis(),
        DeliveryObserver.none());
  }

  private static Envelope stored(String jobId, String shard, long sequence, String mode)
      throws SQLException {
    try (Connection connection = database.getConnection()) {
      connection.setAutoCommit(false);
      new JobRepository().store(connection, TENANT, jobId, shard, AT, mode);
      connection.commit();
    }
    return new Envelope(
        jobId,
        TENANT,
        "plan-api",
        sequence,
        AT,
        Envelope.SCHEMA_VERSION,
        new Envelope.PlanRequested(shard, jobId, mode));
  }

  private static BusRecord record(Envelope envelope) {
    return BusRecord.of(
        Topics.PLAN_TRIGGERS, TENANT + ":" + envelope.eventId(), EnvelopeCodec.write(envelope));
  }

  private static void ensureTenant() throws SQLException {
    try (Connection connection = database.getConnection();
        java.sql.Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (tenant_id) VALUES ('" + TENANT + "') ON CONFLICT DO NOTHING");
    }
  }

  private static void untilQueued(MemoryMessageBus bus) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (bus.queued() > 0L) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("retry was not deferred");
  }

  private static void until(String jobId, String wanted) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (wanted.equals(status(jobId))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("job did not finish");
  }

  private static String status(String jobId) {
    return PlanDatabase.text("SELECT status FROM plan_job WHERE job_id = ?", jobId);
  }

  private static String code(String jobId) {
    return PlanDatabase.text("SELECT code FROM plan_job WHERE job_id = ?", jobId);
  }

  private static long head(String shard) {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?", TENANT, shard);
  }

  private static byte[] snapshot(String shard, long version) {
    return PlanDatabase.bytes(
        "SELECT compressed_plan FROM plan_snapshot WHERE tenant_id = ? AND shard = ? AND version = ?",
        TENANT,
        shard,
        version);
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
