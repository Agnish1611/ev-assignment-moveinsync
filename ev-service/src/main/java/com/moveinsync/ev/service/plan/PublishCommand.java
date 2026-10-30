package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.validator.ValidationInput;
import java.util.Objects;

/**
 * One attempt to make a plan the head of a shard.
 *
 * @param tenant tenant taken from the caller, never from a plan field alone
 * @param shard single-writer shard, usually one shift
 * @param expectedVersion head version the writer observed, 0 when the shard has no head yet
 * @param plan candidate plan
 * @param groundTruth validator input, independent of the candidate's claims
 * @param producer idempotency producer
 * @param sequence idempotency sequence for that producer
 * @param tier replan tier, 0 for a first plan
 * @param ring neighbour ring, 0 when no ring was used
 * @param durationMillis time spent producing the candidate
 * @param changedAssignments assignments that differ from the parent
 * @param outcome short outcome label stored on the replan run
 * @param backupLowerBound backup lower bound stored with the version
 * @param flowLowerBoundMicros flow cost lower bound in micros
 */
public record PublishCommand(
    TenantId tenant,
    String shard,
    long expectedVersion,
    FleetPlan plan,
    ValidationInput groundTruth,
    String producer,
    long sequence,
    int tier,
    int ring,
    long durationMillis,
    long changedAssignments,
    String outcome,
    long backupLowerBound,
    long flowLowerBoundMicros) {

  public PublishCommand {
    Objects.requireNonNull(tenant, "tenant must not be null");
    Objects.requireNonNull(shard, "shard must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    Objects.requireNonNull(groundTruth, "groundTruth must not be null");
    Objects.requireNonNull(producer, "producer must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    if (shard.isBlank()) {
      throw new IllegalArgumentException("shard must not be blank");
    }
    if (producer.isBlank()) {
      throw new IllegalArgumentException("producer must not be blank");
    }
    if (outcome.isBlank()) {
      throw new IllegalArgumentException("outcome must not be blank");
    }
    if (expectedVersion < 0L) {
      throw new IllegalArgumentException("expectedVersion must be non-negative");
    }
    if (sequence < 0L) {
      throw new IllegalArgumentException("sequence must be non-negative");
    }
    if (tier < 0 || ring < 0) {
      throw new IllegalArgumentException("tier and ring must be non-negative");
    }
    if (durationMillis < 0L || changedAssignments < 0L) {
      throw new IllegalArgumentException("duration and changes must be non-negative");
    }
    if (backupLowerBound < 0L || flowLowerBoundMicros < 0L) {
      throw new IllegalArgumentException("bounds must be non-negative");
    }
  }
}
