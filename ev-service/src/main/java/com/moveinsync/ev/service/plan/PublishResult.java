package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.validator.ValidationReport;
import java.util.Objects;

/**
 * Outcome of one publication attempt.
 *
 * @param outcome whether the head moved
 * @param version stored head after the attempt, or 0 when the validator rejected the plan
 * @param report validator report for the candidate
 */
public record PublishResult(PublishOutcome outcome, long version, ValidationReport report) {

  public PublishResult {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(report, "report must not be null");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be non-negative");
    }
  }
}
