package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.PlanJobAccepted;
import com.moveinsync.ev.service.repository.JobRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Accepts a planning request. The job is not a published head: a fleet is not assembled here, and a
 * plan is published only by {@code PlanPublication} after the validator accepts it.
 */
public final class PlanJobs {

  private final IdempotentCalls calls;
  private final JobRepository jobs;
  private final Clock clock;
  private final int maxIdLength;

  public PlanJobs(IdempotentCalls calls, JobRepository jobs, Clock clock, int maxIdLength) {
    this.calls = calls;
    this.jobs = jobs;
    this.clock = clock;
    this.maxIdLength = maxIdLength;
  }

  public ResponseEntity<PlanJobAccepted> accept(Caller caller, String key, String shard) {
    String safeShard = OpaqueIds.require(shard, maxIdLength);
    return calls.once(
        caller,
        key,
        "POST",
        "/v1/plans",
        HttpStatus.ACCEPTED.value(),
        PlanJobAccepted.class,
        connection -> {
          String jobId = UUID.randomUUID().toString();
          jobs.store(connection, caller.tenant(), jobId, safeShard, clock.instant());
          return new PlanJobAccepted(jobId, safeShard, "ACCEPTED");
        });
  }
}
