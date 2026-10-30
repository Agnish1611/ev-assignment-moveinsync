package com.moveinsync.ev.planner.operator;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.trip.TripId;
import java.util.List;
import java.util.Objects;

/**
 * A plan after a destroy operator has lifted some trips out of vehicle schedules.
 *
 * @param kept the plan that remains
 * @param removedTripIds trips the following repair operator may reinsert
 */
public record DestroyedPlan(FleetPlan kept, List<TripId> removedTripIds) {

  public DestroyedPlan {
    Objects.requireNonNull(kept, "kept plan must not be null");
    Objects.requireNonNull(removedTripIds, "removedTripIds must not be null");
    removedTripIds = List.copyOf(removedTripIds);
  }
}
