package com.moveinsync.ev.service.observe;

import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import java.util.List;
import java.util.Objects;

/** Figures recorded when a validator-clean plan is committed. Explanations are not included. */
public record PublishedSample(
    int tier,
    long durationMillis,
    int backups,
    long chargingCostMicros,
    long flowLowerBoundMicros,
    List<UnassignedReasonCode> reasons) {

  public PublishedSample {
    reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
  }
}
