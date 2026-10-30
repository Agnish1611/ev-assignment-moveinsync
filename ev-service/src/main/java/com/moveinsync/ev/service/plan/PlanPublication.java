package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationReport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import javax.sql.DataSource;

/**
 * Stores a plan as the shard head only after the independent validator accepts it. The
 * compare-and-set, the version, the diff, the snapshot, the read model, the outbox row, and the
 * processed event commit in one transaction.
 *
 * <p>Time O(A) for A activities. Space O(A).
 */
public final class PlanPublication {

  private final PlanValidator validator;
  private final PlanHeadRepository heads;
  private final PlanReadRepository reads;
  private final Clock clock;

  public PlanPublication(
      PlanValidator validator, PlanHeadRepository heads, PlanReadRepository reads, Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator must not be null");
    this.heads = Objects.requireNonNull(heads, "heads must not be null");
    this.reads = Objects.requireNonNull(reads, "reads must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public PublishResult publish(DataSource dataSource, PublishCommand command) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    Objects.requireNonNull(command, "command must not be null");
    if (!command.plan().tenantId().equals(command.tenant().value())) {
      throw new IllegalArgumentException("plan tenant does not match the session tenant");
    }
    ValidationReport report = validator.validate(command.groundTruth(), command.plan());
    if (!report.isValid()) {
      return new PublishResult(PublishOutcome.REJECTED, 0L, report);
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      return write(connection, command, report);
    } catch (SQLException ex) {
      throw new PlanStoreException("plan publication failed", ex);
    }
  }

  private PublishResult write(
      Connection connection, PublishCommand command, ValidationReport report) throws SQLException {
    try {
      PublishResult result = attempt(connection, command, report);
      if (result.outcome() == PublishOutcome.PUBLISHED) {
        connection.commit();
        return result;
      }
      connection.rollback();
      if (result.outcome() == PublishOutcome.REPLAYED) {
        return result;
      }
      return raced(connection, command, report);
    } catch (SQLException ex) {
      rollback(connection, ex);
      throw ex;
    } catch (RuntimeException ex) {
      rollback(connection, ex);
      throw abort(ex);
    }
  }

  private static IllegalStateException abort(RuntimeException failure) {
    if (failure instanceof IllegalArgumentException illegal) {
      throw illegal;
    }
    if (failure instanceof IllegalStateException illegal) {
      throw illegal;
    }
    return new IllegalStateException(failure.getMessage(), failure);
  }

  private PublishResult attempt(
      Connection connection, PublishCommand command, ValidationReport report) throws SQLException {
    Instant now = clock.instant();
    String tenant = command.tenant().value();
    TenantSession.apply(connection, tenant);
    heads.ensureTenant(connection, tenant);
    OptionalLong existing =
        heads.processedVersion(connection, tenant, command.producer(), command.sequence());
    if (existing.isPresent()) {
      return new PublishResult(PublishOutcome.REPLAYED, existing.orElseThrow(), report);
    }
    OptionalLong claimed =
        heads.claim(connection, tenant, command.shard(), command.expectedVersion(), now);
    if (claimed.isEmpty()) {
      return new PublishResult(PublishOutcome.CONFLICT, 0L, report);
    }
    long version = claimed.orElseThrow();
    store(connection, command, report, tenant, version, now);
    return new PublishResult(PublishOutcome.PUBLISHED, version, report);
  }

  private void store(
      Connection connection,
      PublishCommand command,
      ValidationReport report,
      String tenant,
      long version,
      Instant now)
      throws SQLException {
    OptionalLong parent = version == 1L ? OptionalLong.empty() : OptionalLong.of(version - 1L);
    Map<String, String> before =
        version == 1L
            ? Map.of()
            : reads.assignments(connection, tenant, command.shard(), version - 1L);
    heads.insertVersion(
        connection,
        tenant,
        command.shard(),
        version,
        parent,
        command.plan(),
        objective(command),
        bounds(command),
        report.toDetailedSummary(),
        now);
    heads.insertDiff(
        connection,
        tenant,
        command.shard(),
        version,
        PlanDiffs.between(before, after(command.plan())));
    Optional<Instant> last = heads.latestSnapshotAt(connection, tenant, command.shard());
    if (SnapshotPolicy.due(version, last, now)) {
      heads.insertSnapshot(
          connection, tenant, command.shard(), version, PlanDocuments.gzip(command.plan()), now);
    }
    reads.insertStops(connection, tenant, command.shard(), version, command.plan());
    reads.insertSessions(connection, tenant, command.shard(), version, command.plan());
    reads.insertUnassigned(connection, tenant, command.shard(), version, command.plan());
    heads.insertProcessed(
        connection, tenant, command.producer(), command.sequence(), command.shard(), version);
    heads.insertOutbox(connection, tenant, command.shard(), version, now);
    heads.insertReplan(
        connection,
        tenant,
        command.shard(),
        version,
        command.tier(),
        command.ring(),
        command.durationMillis(),
        command.changedAssignments(),
        command.outcome());
    heads.insertAudit(connection, tenant, now);
  }

  private PublishResult raced(
      Connection connection, PublishCommand command, ValidationReport report) throws SQLException {
    TenantSession.apply(connection, command.tenant().value());
    String tenant = command.tenant().value();
    OptionalLong existing =
        heads.processedVersion(connection, tenant, command.producer(), command.sequence());
    OptionalLong head = heads.headVersion(connection, tenant, command.shard());
    connection.commit();
    if (existing.isPresent()) {
      return new PublishResult(PublishOutcome.REPLAYED, existing.orElseThrow(), report);
    }
    return new PublishResult(PublishOutcome.CONFLICT, head.orElse(0L), report);
  }

  private static Map<String, String> after(FleetPlan plan) {
    Map<String, String> found = new HashMap<>();
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment trip) {
          found.put(trip.trip().id().value(), schedule.vehicleId().value());
        }
      }
    }
    return found;
  }

  private static String objective(PublishCommand command) {
    return "{\"unassigned\":"
        + command.plan().totalUnassignedTrips()
        + ",\"chargingCostMicros\":"
        + command.plan().totalChargingCostMicros()
        + ",\"changedAssignments\":"
        + command.changedAssignments()
        + "}";
  }

  private static String bounds(PublishCommand command) {
    return "{\"backupLowerBound\":"
        + command.backupLowerBound()
        + ",\"flowLowerBoundMicros\":"
        + command.flowLowerBoundMicros()
        + "}";
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException ex) {
      failure.addSuppressed(ex);
    }
  }
}
