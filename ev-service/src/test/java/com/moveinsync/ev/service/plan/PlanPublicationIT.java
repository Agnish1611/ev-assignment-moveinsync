package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A validator-clean plan becomes the shard head. A loser or a replay does not. */
class PlanPublicationIT {

  private static final String TENANT = ValidFleet.TENANT.value();
  private static DataSource dataSource;
  private static FleetPlan plan;
  private static ValidationInput groundTruth;

  @BeforeAll
  static void open() {
    dataSource = PlanDatabase.open();
    PlannerInput fleet = ValidFleet.input();
    plan = ValidFleet.plan(fleet);
    groundTruth = ValidFleet.groundTruth(fleet);
    assertThat(new PlanValidator().validate(groundTruth, plan).isValid()).isTrue();
  }

  @Test
  void aValidatedPlanBecomesHeadAndRestoresFromTheSnapshot() {
    PublishResult result = publish("shard-first", 0L, 1L, clock());

    assertThat(result.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
    assertThat(result.version()).isEqualTo(1L);
    assertThat(head("shard-first")).isEqualTo(1L);
    assertThat(PlanDocuments.gunzip(snapshot("shard-first", 1L)))
        .usingRecursiveComparison()
        .isEqualTo(plan);
    assertThat(diff("shard-first", 1L)).contains("TRIP-1");
    assertThat(count("route_stop", "shard-first")).isEqualTo(plan.totalAssignedTrips());
    assertThat(count("outbox", "shard-first")).isEqualTo(1L);
    assertThat(count("replan_run", "shard-first")).isEqualTo(1L);
    assertThat(
            PlanDatabase.count(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
                TENANT,
                "PLAN_PUBLISHED"))
        .isGreaterThanOrEqualTo(1L);
  }

  @Test
  void aFollowUpInsideTenMinutesStoresADiffWithoutAnotherSnapshot() {
    publish("shard-follow", 0L, 1L, clock());

    PublishResult second = publish("shard-follow", 1L, 2L, clock());

    assertThat(second.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
    assertThat(second.version()).isEqualTo(2L);
    assertThat(count("plan_snapshot", "shard-follow")).isEqualTo(1L);
    assertThat(diff("shard-follow", 2L)).isEmpty();
  }

  @Test
  void aVersionAfterTenMinutesStoresAnotherSnapshot() {
    MovingClock clock = clock();
    publish("shard-aged", 0L, 1L, clock);
    clock.advance(Duration.ofSeconds(SnapshotPolicy.MAX_AGE_SECONDS));

    publish("shard-aged", 1L, 2L, clock);

    assertThat(count("plan_snapshot", "shard-aged")).isEqualTo(2L);
  }

  @Test
  void theSameProducerSequenceIsAReplay() {
    PublishResult first = publish("shard-replay", 0L, 4L, clock());

    PublishResult second = publish("shard-replay", 0L, 4L, clock());

    assertThat(first.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
    assertThat(second.outcome()).isEqualTo(PublishOutcome.REPLAYED);
    assertThat(second.version()).isEqualTo(1L);
    assertThat(head("shard-replay")).isEqualTo(1L);
    assertThat(count("processed_event", "shard-replay")).isEqualTo(1L);
  }

  @Test
  void aStaleExpectedVersionConflicts() {
    publish("shard-stale", 0L, 1L, clock());

    PublishResult stale = publish("shard-stale", 0L, 2L, clock());

    assertThat(stale.outcome()).isEqualTo(PublishOutcome.CONFLICT);
    assertThat(stale.version()).isEqualTo(1L);
    assertThat(head("shard-stale")).isEqualTo(1L);
  }

  @Test
  void anInvalidPlanDoesNotMoveTheHead() {
    FleetPlan empty =
        new FleetPlan(
            "empty", TENANT, ValidFleet.START, ValidFleet.END, List.of(), List.of(), 1L, "h");
    PublishCommand command =
        new PublishCommand(
            ValidFleet.TENANT,
            "shard-reject",
            0L,
            empty,
            groundTruth,
            "producer-shard-reject",
            1L,
            0,
            0,
            1L,
            0L,
            "PUBLISHED",
            0L,
            0L);

    PublishResult result =
        new PlanPublication(
                new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock())
            .publish(dataSource, command);

    assertThat(result.outcome()).isEqualTo(PublishOutcome.REJECTED);
    assertThat(result.report().isValid()).isFalse();
    assertThat(head("shard-reject")).isZero();
  }

  @Test
  void twoWritersCannotBothAdvanceTheHead() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<PublishResult> first = pool.submit(() -> publish("shard-race", 0L, 1L, clock()));
      Future<PublishResult> second = pool.submit(() -> publish("shard-race", 0L, 2L, clock()));
      PublishResult left = get(first);
      PublishResult right = get(second);

      assertThat(List.of(left.outcome(), right.outcome()))
          .containsExactlyInAnyOrder(PublishOutcome.PUBLISHED, PublishOutcome.CONFLICT);
      assertThat(head("shard-race")).isEqualTo(1L);
      assertThat(count("plan_version", "shard-race")).isEqualTo(1L);
    } finally {
      pool.shutdownNow();
    }
  }

  private static PublishResult publish(String shard, long expected, long sequence, Clock clock) {
    PublishCommand command =
        new PublishCommand(
            ValidFleet.TENANT,
            shard,
            expected,
            plan,
            groundTruth,
            "producer-" + shard,
            sequence,
            1,
            0,
            5L,
            0L,
            "PUBLISHED",
            0L,
            0L);
    return new PlanPublication(
            new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), clock)
        .publish(dataSource, command);
  }

  private static MovingClock clock() {
    return new MovingClock(ValidFleet.START);
  }

  private static long head(String shard) {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?", TENANT, shard);
  }

  private static long count(String table, String shard) {
    return PlanDatabase.count(
        "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND shard = ?", TENANT, shard);
  }

  private static String diff(String shard, long version) {
    return PlanDatabase.text(
        "SELECT changes FROM plan_diff WHERE tenant_id = ? AND shard = ? AND version = ?",
        TENANT,
        shard,
        version);
  }

  private static byte[] snapshot(String shard, long version) {
    return PlanDatabase.bytes(
        "SELECT compressed_plan FROM plan_snapshot WHERE tenant_id = ? AND shard = ? AND version = ?",
        TENANT,
        shard,
        version);
  }

  private static PublishResult get(Future<PublishResult> future)
      throws InterruptedException, ExecutionException {
    try {
      return future.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ex;
    }
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
