package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.repository.JobRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** Moves an accepted job to RUNNING until the lease instant. */
public final class JobClaims {

  private final JobRepository jobs;
  private final JobSettings settings;

  public JobClaims(JobRepository jobs, JobSettings settings) {
    this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
  }

  public int claim(Connection connection, String tenant, String jobId, Instant now)
      throws SQLException {
    return jobs.claim(connection, tenant, jobId, now.plusSeconds(settings.leaseSeconds()));
  }
}
