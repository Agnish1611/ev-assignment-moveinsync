package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.construct.FleetLowerBound;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.ValidationReport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/** Turns a solved plan into a publication against the validator's ground truth. */
final class JobPublisher {

  private final DataSource dataSource;
  private final PlanPublication publication;
  private final PlanReadRepository reads;
  private final Clock clock;

  JobPublisher(
      DataSource dataSource, PlanPublication publication, PlanReadRepository reads, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.publication = Objects.requireNonNull(publication, "publication must not be null");
    this.reads = Objects.requireNonNull(reads, "reads must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  PublishResult publish(
      Envelope envelope,
      Envelope.PlanRequested requested,
      long expected,
      Instant started,
      SolveResult result,
      LoadedFleet fresh) {
    long millis = Math.max(0L, Duration.between(started, clock.instant()).toMillis());
    return publication.publish(
        dataSource, command(envelope, requested, expected, result, fresh, millis));
  }

  private PublishCommand command(
      Envelope envelope,
      Envelope.PlanRequested requested,
      long expected,
      SolveResult result,
      LoadedFleet fresh,
      long millis) {
    return new PublishCommand(
        TenantId.of(envelope.tenant()),
        requested.shard(),
        expected,
        result.plan(),
        fresh.validation(),
        envelope.producer(),
        envelope.sequence(),
        0,
        0,
        millis,
        changed(envelope.tenant(), requested.shard(), expected, result.plan()),
        requested.mode(),
        FleetLowerBound.of(fresh.planner()).backups(),
        result.flow().relaxationMicros());
  }

  private long changed(String tenant, String shard, long parent, FleetPlan plan) {
    if (parent <= 0L) {
      return 0L;
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      Map<String, String> before = reads.assignments(connection, tenant, shard, parent);
      connection.rollback();
      return delta(before, assignments(plan));
    } catch (SQLException ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  private static long delta(Map<String, String> before, Map<String, String> after) {
    long changed = 0L;
    for (Map.Entry<String, String> entry : after.entrySet()) {
      if (!entry.getValue().equals(before.get(entry.getKey()))) {
        changed++;
      }
    }
    for (String trip : before.keySet()) {
      if (!after.containsKey(trip)) {
        changed++;
      }
    }
    return changed;
  }

  private static Map<String, String> assignments(FleetPlan plan) {
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

  static String rule(ValidationReport report) {
    if (report.violations().isEmpty()) {
      return "REJECTED";
    }
    return report.violations().get(0).ruleId().name();
  }
}
