package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.TriggerSequences;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Writes the job and its trigger before the caller commits. A failure between the two statements
 * rolls both back.
 */
public final class JobAcceptance {

  static final String PRODUCER = "plan-api";

  private final JobRepository jobs;
  private final PlanHeadRepository heads;
  private final TriggerSequences sequences;

  public JobAcceptance(JobRepository jobs, PlanHeadRepository heads, TriggerSequences sequences) {
    this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
    this.heads = Objects.requireNonNull(heads, "heads must not be null");
    this.sequences = Objects.requireNonNull(sequences, "sequences must not be null");
  }

  public Result write(Connection connection, Request request, Runnable between)
      throws SQLException {
    Objects.requireNonNull(between, "between must not be null");
    String mode = mode(connection, request);
    jobs.store(connection, request.tenant(), request.jobId(), request.shard(), request.at(), mode);
    between.run();
    long sequence = sequences.next(connection, request.tenant(), PRODUCER);
    heads.insertOutbox(
        connection,
        request.tenant(),
        request.shard(),
        0L,
        encoded(request, mode, sequence),
        request.at(),
        Topics.PLAN_TRIGGERS);
    return new Result(request.jobId(), mode, sequence);
  }

  private String mode(Connection connection, Request request) throws SQLException {
    OptionalLong head = heads.headVersion(connection, request.tenant(), request.shard());
    String requested = request.mode();
    if (requested == null || requested.isBlank()) {
      return head.isPresent() ? Envelope.PlanRequested.FULL_REPLAN : Envelope.PlanRequested.INITIAL;
    }
    if (Envelope.PlanRequested.INITIAL.equals(requested)) {
      if (head.isPresent()) {
        throw new ApiException(409, "HEAD_EXISTS", "A plan head already exists");
      }
      return requested;
    }
    if (Envelope.PlanRequested.FULL_REPLAN.equals(requested)) {
      if (head.isEmpty()) {
        throw new ApiException(409, "NO_HEAD", "No plan head exists");
      }
      return requested;
    }
    throw new ApiException(400, "INVALID_MODE", "mode is not supported");
  }

  private static String encoded(Request request, String mode, long sequence) {
    Envelope envelope =
        new Envelope(
            request.jobId(),
            request.tenant(),
            PRODUCER,
            sequence,
            request.at(),
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanRequested(request.shard(), request.jobId(), mode));
    return Base64.getEncoder().encodeToString(EnvelopeCodec.write(envelope));
  }

  /** The caller's plan post. {@code mode} is null when the caller did not choose one. */
  public record Request(String tenant, String shard, String mode, String jobId, Instant at) {}

  /** What was stored. {@code sequence} is the trigger sequence. */
  public record Result(String jobId, String mode, long sequence) {}
}
