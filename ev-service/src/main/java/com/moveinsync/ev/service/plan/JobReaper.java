package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.service.api.JobSettings;
import com.moveinsync.ev.service.repository.JobRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Returns a job to ACCEPTED when its solve lease has passed. */
public final class JobReaper {

  private static final Logger log = LoggerFactory.getLogger(JobReaper.class);

  private final DataSource dataSource;
  private final JobRepository jobs;
  private final Clock clock;

  public JobReaper(DataSource dataSource, JobRepository jobs, Clock clock, JobSettings settings) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(settings, "settings must not be null");
    if (settings.leaseSeconds() <= 0 || settings.reapMillis() <= 0L) {
      throw new IllegalArgumentException("job timings must be positive");
    }
  }

  @Scheduled(fixedDelayString = "${ev.job.reap-millis:5000}")
  public void publish() {
    try {
      reap();
    } catch (RuntimeException ex) {
      log.warn("job reap failed {}", ex.getClass().getSimpleName());
    }
  }

  public int reap() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        RelaySession.apply(connection);
        int updated = jobs.reap(connection, clock.instant());
        connection.commit();
        return updated;
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
      throw stored;
    }
    return new PlanStoreException("job reap failed", failure);
  }
}
