package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.PlanJobAccepted;
import com.moveinsync.ev.service.api.ApiModels.PlanJobRequest;
import com.moveinsync.ev.service.api.ApiModels.PlanJobStatus;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.JobRepository.JobView;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.TriggerSequences;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Accepts a planning request. The job and its trigger commit together. A fleet is not assembled
 * here, and a plan is published only by {@code PlanPublication} after the validator accepts it.
 */
public final class PlanJobs {

  private final IdempotentCalls calls;
  private final AppConnections connections;
  private final JobAcceptance acceptance;
  private final JobRepository jobs;
  private final Clock clock;
  private final int maxIdLength;

  public PlanJobs(IdempotentCalls calls, AppConnections connections, Clock clock, int maxIdLength) {
    this.calls = calls;
    this.connections = connections;
    JobRepository stored = new JobRepository();
    this.jobs = stored;
    this.acceptance = new JobAcceptance(stored, new PlanHeadRepository(), new TriggerSequences());
    this.clock = clock;
    this.maxIdLength = maxIdLength;
  }

  public ResponseEntity<PlanJobAccepted> accept(Caller caller, String key, PlanJobRequest request) {
    String shard = OpaqueIds.require(request.shard(), maxIdLength);
    return calls.once(
        caller,
        key,
        "POST",
        "/v1/plans",
        HttpStatus.ACCEPTED.value(),
        PlanJobAccepted.class,
        connection -> {
          String jobId = UUID.randomUUID().toString();
          acceptance.write(
              connection,
              new JobAcceptance.Request(
                  caller.tenant(), shard, request.mode(), jobId, clock.instant()),
              () -> {});
          return new PlanJobAccepted(jobId, shard, "ACCEPTED");
        });
  }

  public PlanJobStatus status(Caller caller, String jobId) {
    String safeId = OpaqueIds.require(jobId, maxIdLength);
    return connections.read(
        caller.tenant(),
        connection -> {
          JobView job =
              jobs.find(connection, caller.tenant(), safeId)
                  .orElseThrow(
                      () -> new ApiException(404, "JOB_NOT_FOUND", "The job was not found"));
          return new PlanJobStatus(
              job.jobId(),
              job.shard(),
              job.status(),
              job.code(),
              job.publishedVersion(),
              job.mode());
        });
  }
}
