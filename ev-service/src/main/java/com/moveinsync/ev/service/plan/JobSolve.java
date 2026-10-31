package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.service.api.JobClaims;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.fleet.FleetRejected;
import com.moveinsync.ev.service.fleet.FleetSource;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.service.observe.DeliveryObserver;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Solves one plan request and publishes only when the validator accepts a freshly loaded fleet.
 *
 * <p>Time O(T² · C · H · P) for T trips, C cabs, H hubs, and P plugs. Space O(T · C). A catalog
 * that moves during the solve is solved once more. A second move is {@code STALE_INPUT} and is not
 * published.
 */
public final class JobSolve {

  static final String STORE_FAILED = "STORE_FAILED";
  static final String DEAD = "DEAD_LETTER";
  static final String MOVED = "CATALOG_MOVED";

  private static final Logger log = LoggerFactory.getLogger(JobSolve.class);

  private final JobLedger ledger;
  private final JobPublisher publisher;
  private final DriftSolve drifts;
  private final FleetSource fleets;
  private final Clock clock;
  private final long solveMillis;
  private final DeliveryObserver observer;

  public JobSolve(
      DataSource dataSource,
      JobRepository jobs,
      JobClaims claims,
      FleetSource fleets,
      PlanPublication publication,
      PlanReadRepository reads,
      PlanHeadRepository heads,
      Clock clock,
      long solveMillis,
      DeliveryObserver observer) {
    this.fleets = Objects.requireNonNull(fleets, "fleets must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.observer = Objects.requireNonNull(observer, "observer must not be null");
    if (solveMillis <= 0L) {
      throw new IllegalArgumentException("solveMillis must be positive");
    }
    this.solveMillis = solveMillis;
    this.ledger = new JobLedger(dataSource, jobs, claims, heads, clock);
    this.publisher = new JobPublisher(dataSource, publication, reads, clock);
    this.drifts = new DriftSolve(dataSource, fleets, heads, publisher, clock, solveMillis);
  }

  public void accept(Envelope envelope) {
    if (envelope.payload() instanceof Envelope.PlanTrigger) {
      drifts.accept(envelope);
      return;
    }
    if (!(envelope.payload() instanceof Envelope.PlanRequested requested)) {
      return;
    }
    if (!ledger.claim(envelope.tenant(), requested.jobId())) {
      return;
    }
    try {
      solve(envelope, requested);
    } catch (RuntimeException ex) {
      ledger.release(envelope.tenant(), requested.jobId(), ex);
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  public void dead(BusRecord record) {
    Envelope envelope = read(record);
    if (envelope != null && envelope.payload() instanceof Envelope.PlanRequested requested) {
      ledger.abandon(envelope.tenant(), requested.jobId(), "FAILED", DEAD);
    }
  }

  private void solve(Envelope envelope, Envelope.PlanRequested requested) {
    LoadedFleet first = readFleet(envelope.tenant(), requested);
    if (first == null) {
      return;
    }
    long expected = expected(envelope.tenant(), requested);
    if (expected < 0L) {
      return;
    }
    Instant started = clock.instant();
    SolveResult result = run(first);
    LoadedFleet fresh = readFleet(envelope.tenant(), requested);
    if (fresh == null) {
      return;
    }
    if (fresh.catalogVersion() != first.catalogVersion()) {
      result = run(fresh);
      fresh = held(envelope, requested, fresh);
      if (fresh == null) {
        return;
      }
      expected = expected(envelope.tenant(), requested);
      if (expected < 0L) {
        return;
      }
    }
    record(envelope, requested, expected, started, result, fresh);
  }

  private LoadedFleet held(
      Envelope envelope, Envelope.PlanRequested requested, LoadedFleet solved) {
    LoadedFleet third = readFleet(envelope.tenant(), requested);
    if (third == null) {
      return null;
    }
    if (third.catalogVersion() != solved.catalogVersion()) {
      ledger.finish(envelope.tenant(), requested.jobId(), "STALE_INPUT", MOVED, 0L);
      return null;
    }
    return third;
  }

  private void record(
      Envelope envelope,
      Envelope.PlanRequested requested,
      long expected,
      Instant started,
      SolveResult result,
      LoadedFleet fresh) {
    PublishResult published =
        publisher.publish(envelope, requested, expected, started, result, fresh);
    switch (published.outcome()) {
      case PUBLISHED, REPLAYED ->
          ledger.finish(envelope.tenant(), requested.jobId(), "PUBLISHED", "", published.version());
      case REJECTED ->
          ledger.finish(
              envelope.tenant(),
              requested.jobId(),
              "REJECTED",
              JobPublisher.rule(published.report()),
              0L);
      case CONFLICT ->
          ledger.finish(envelope.tenant(), requested.jobId(), "FAILED", "CONFLICT", 0L);
    }
  }

  private LoadedFleet readFleet(String tenant, Envelope.PlanRequested requested) {
    try {
      return fleets.load(tenant, requested.shard());
    } catch (FleetRejected rejected) {
      if (STORE_FAILED.equals(rejected.code())) {
        throw rejected;
      }
      ledger.finish(tenant, requested.jobId(), "INFEASIBLE", rejected.code(), 0L);
      return null;
    }
  }

  private long expected(String tenant, Envelope.PlanRequested requested) {
    if (Envelope.PlanRequested.INITIAL.equals(requested.mode())) {
      return 0L;
    }
    OptionalLong head = ledger.head(tenant, requested.shard());
    if (head.isEmpty()) {
      ledger.finish(tenant, requested.jobId(), "FAILED", "NO_HEAD", 0L);
      return -1L;
    }
    return head.orElseThrow();
  }

  private SolveResult run(LoadedFleet fleet) {
    SolverConfig config =
        SolverConfig.safeBaseline(clock.instant().plusMillis(solveMillis), fleet.catalogVersion());
    return new GreedyConstructionSolver().solve(fleet.planner(), config, clock);
  }

  private Envelope read(BusRecord record) {
    try {
      return EnvelopeCodec.read(record.value());
    } catch (RuntimeException ex) {
      log.warn("dead letter was not applied {}", ex.getClass().getSimpleName());
      observer.deadLettered();
      return null;
    }
  }
}
