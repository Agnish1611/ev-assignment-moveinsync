package com.moveinsync.ev.planner.construct;

/** The first constraint that rejects a cab, from the earliest check to a feasible insertion. */
public enum BindingConstraint {
  ROUTE(1),
  TIME(2),
  ENERGY(3),
  RESERVE(4),
  CONGESTED(5),
  FEASIBLE(6);

  private final int rank;

  BindingConstraint(int rank) {
    this.rank = rank;
  }

  int rank() {
    return rank;
  }

  static BindingConstraint of(AppendProbe.Block block) {
    return switch (block) {
      case ROUTE -> ROUTE;
      case TIME -> TIME;
      case ENERGY -> ENERGY;
      case RESERVE -> RESERVE;
      case CONGESTED -> CONGESTED;
      case FEASIBLE -> FEASIBLE;
    };
  }
}
