package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.LadderReplanSolver;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.PlannerWorker;
import com.moveinsync.ev.service.fleet.FleetRejected;
import com.moveinsync.ev.service.fleet.FleetSource;
import com.moveinsync.ev.service.fleet.FleetState;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import javax.sql.DataSource;

/**
 * Repairs one drift trigger against the published head and publishes only a validator-clean plan.
 *
 * <p>Time O(T² · C · H · P) for T trips, C cabs, H hubs, and P plugs. Space O(T · C). The ladder
 * clock is the trigger's observation time, so the lock window follows the event. A catalog move
 * during the solve is retried by the bus. A rejected candidate leaves the previous head.
 */
final class DriftSolve {

  private final DataSource dataSource;
  private final FleetSource fleets;
  private final PlanHeadRepository heads;
  private final JobPublisher publisher;
  private final FleetState state;
  private final Clock clock;
  private final long solveMillis;

  DriftSolve(
      DataSource dataSource,
      FleetSource fleets,
      PlanHeadRepository heads,
      JobPublisher publisher,
      Clock clock,
      long solveMillis) {
    this.dataSource = dataSource;
    this.fleets = fleets;
    this.heads = heads;
    this.publisher = publisher;
    this.clock = clock;
    this.solveMillis = solveMillis;
    this.state = new FleetState();
  }

  void accept(Envelope envelope) {
    if (!(envelope.payload() instanceof Envelope.PlanTrigger trigger)) {
      return;
    }
    apply(envelope, trigger);
    LoadedFleet fleet = fleets.load(envelope.tenant(), trigger.shard());
    FleetPlan current = current(envelope.tenant(), trigger.shard());
    if (current == null) {
      return;
    }
    Instant started = clock.instant();
    SolveResult result = climb(trigger, fleet, current, envelope);
    LoadedFleet fresh = fleets.load(envelope.tenant(), trigger.shard());
    if (fresh.catalogVersion() != fleet.catalogVersion()) {
      throw new PlanStoreException("job solve failed", new IllegalStateException("catalog moved"));
    }
    long expected = version(envelope.tenant(), trigger.shard());
    publisher.publish(envelope, trigger.shard(), trigger.kind(), expected, started, result, fresh);
  }

  private SolveResult climb(
      Envelope.PlanTrigger trigger, LoadedFleet fleet, FleetPlan current, Envelope envelope) {
    Clock solver = Clock.fixed(trigger.observedAt(), ZoneOffset.UTC);
    SolverConfig config =
        SolverConfig.safeBaseline(
            trigger.observedAt().plusMillis(solveMillis), fleet.catalogVersion());
    return new LadderReplanSolver()
        .replan(fleet.planner(), current, PlannerWorker.drift(envelope), config, solver);
  }

  private void apply(Envelope envelope, Envelope.PlanTrigger trigger) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, envelope.tenant());
      state.apply(connection, envelope, trigger);
      connection.commit();
    } catch (SQLException | FleetRejected ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  private FleetPlan current(String tenant, String shard) {
    long version = version(tenant, shard);
    if (version < 0L) {
      return null;
    }
    byte[] image = image(tenant, shard, version);
    if (image.length == 0) {
      throw new PlanStoreException(
          "job solve failed", new IllegalStateException("snapshot missing"));
    }
    return PlanDocuments.gunzip(image);
  }

  private long version(String tenant, String shard) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      OptionalLong head = heads.headVersion(connection, tenant, shard);
      connection.rollback();
      if (head.isEmpty()) {
        return -1L;
      }
      return head.orElseThrow();
    } catch (SQLException ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  private byte[] image(String tenant, String shard, long version) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      byte[] image = state.headImage(connection, tenant, shard, version);
      connection.rollback();
      return image;
    } catch (SQLException ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }
}
