package com.moveinsync.ev.planner.telemetry;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Filters cab readings before they become planner triggers.
 *
 * <p>A jump faster than the policy is quarantined, unless the reading is already under the critical
 * floor. Silence replaces the last reading with a lower estimate. One cab is updated at a time by
 * its caller.
 *
 * <p>Time O(1) per reading and O(C) per silence scan. Space O(C) for C cabs.
 */
public final class TelemetryGate {

  private final TelemetryPolicy policy;
  private final Map<String, CabLink> cabs = new HashMap<>();

  public TelemetryGate(TelemetryPolicy policy) {
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
  }

  public Optional<DriftEvent> observe(
      String cabId, long socWh, OptionalLong expectedWh, Instant observedAt) {
    Objects.requireNonNull(cabId, "cabId must not be null");
    Objects.requireNonNull(expectedWh, "expectedWh must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    if (cabId.isBlank() || cabId.contains("@") || cabId.startsWith("+")) {
      throw new IllegalArgumentException("cabId must be an opaque identifier");
    }
    if (socWh < 0L) {
      throw new IllegalArgumentException("socWh must be non-negative");
    }
    if (expectedWh.isPresent() && expectedWh.orElseThrow() < 0L) {
      throw new IllegalArgumentException("expectedWh must be non-negative");
    }
    CabLink link = cabs.computeIfAbsent(cabId, CabLink::new);
    return link.accept(socWh, expectedWh, observedAt, policy);
  }

  public List<DriftEvent> silence(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    List<DriftEvent> found = new ArrayList<>();
    for (CabLink link : cabs.values()) {
      link.silence(now, policy).ifPresent(found::add);
    }
    return List.copyOf(found);
  }
}
