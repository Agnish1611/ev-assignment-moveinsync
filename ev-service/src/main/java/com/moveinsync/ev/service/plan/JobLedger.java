package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.service.api.JobClaims;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;
import javax.sql.DataSource;

/** Claims, finishes, and reads a plan job in the tenant copied from the trigger. */
final class JobLedger {

  private final DataSource dataSource;
  private final JobRepository jobs;
  private final JobClaims claims;
  private final PlanHeadRepository heads;
  private final Clock clock;

  JobLedger(
      DataSource dataSource,
      JobRepository jobs,
      JobClaims claims,
      PlanHeadRepository heads,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
    this.claims = Objects.requireNonNull(claims, "claims must not be null");
    this.heads = Objects.requireNonNull(heads, "heads must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  boolean claim(String tenant, String jobId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        WorkerSession.apply(connection, tenant);
        boolean claimed = take(connection, tenant, jobId);
        if (claimed) {
          connection.commit();
        } else {
          connection.rollback();
        }
        return claimed;
      } catch (RuntimeException | SQLException ex) {
        connection.rollback();
        throw failed(ex);
      }
    } catch (SQLException ex) {
      throw failed(ex);
    }
  }

  OptionalLong head(String tenant, String shard) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      OptionalLong version = heads.headVersion(connection, tenant, shard);
      connection.rollback();
      return version;
    } catch (SQLException ex) {
      throw failed(ex);
    }
  }

  void finish(String tenant, String jobId, String status, String code, long version) {
    update(tenant, connection -> jobs.finish(connection, tenant, jobId, status, code, version));
  }

  void abandon(String tenant, String jobId, String status, String code) {
    update(tenant, connection -> jobs.abandon(connection, tenant, jobId, status, code));
  }

  void release(String tenant, String jobId, RuntimeException failure) {
    try {
      update(tenant, connection -> jobs.release(connection, tenant, jobId));
    } catch (RuntimeException ex) {
      failure.addSuppressed(ex);
    }
  }

  private boolean take(Connection connection, String tenant, String jobId) throws SQLException {
    JobRepository.JobView job =
        jobs.find(connection, tenant, jobId)
            .orElseThrow(() -> new IllegalStateException("job was not found"));
    if (!"ACCEPTED".equals(job.status())) {
      if ("RUNNING".equals(job.status())) {
        throw new IllegalStateException("job is already running");
      }
      return false;
    }
    return claims.claim(connection, tenant, jobId, clock.instant()) == 1;
  }

  private void update(String tenant, SqlWork work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        WorkerSession.apply(connection, tenant);
        work.run(connection);
        connection.commit();
      } catch (RuntimeException | SQLException ex) {
        connection.rollback();
        throw failed(ex);
      }
    } catch (SQLException ex) {
      throw failed(ex);
    }
  }

  private static PlanStoreException failed(Exception failure) {
    if (failure instanceof PlanStoreException stored) {
      return stored;
    }
    return new PlanStoreException("job solve failed", failure);
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection connection) throws SQLException;
  }
}
