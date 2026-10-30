package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import java.util.Optional;

/** Result of asking one cab whether it can take a trip at the end of its current route. */
final class AppendProbe {

  enum Block {
    FEASIBLE,
    ROUTE,
    ENERGY,
    RESERVE,
    TIME,
    CONGESTED
  }

  private final Block block;
  private final Optional<ChargingSession> charge;
  private final Optional<TripAssignment> assignment;
  private final long energyWh;

  private AppendProbe(
      Block block,
      Optional<ChargingSession> charge,
      Optional<TripAssignment> assignment,
      long energyWh) {
    this.block = block;
    this.charge = charge;
    this.assignment = assignment;
    this.energyWh = energyWh;
  }

  static AppendProbe blocked(Block block) {
    return new AppendProbe(block, Optional.empty(), Optional.empty(), Long.MAX_VALUE);
  }

  static AppendProbe placed(TripAssignment assignment, long energyWh) {
    return new AppendProbe(Block.FEASIBLE, Optional.empty(), Optional.of(assignment), energyWh);
  }

  static AppendProbe charged(ChargingSession session, TripAssignment assignment, long energyWh) {
    return new AppendProbe(Block.FEASIBLE, Optional.of(session), Optional.of(assignment), energyWh);
  }

  Block block() {
    return block;
  }

  Optional<ChargingSession> charge() {
    return charge;
  }

  Optional<TripAssignment> assignment() {
    return assignment;
  }

  long energyWh() {
    return energyWh;
  }

  boolean feasible() {
    return block == Block.FEASIBLE;
  }
}
