package com.moveinsync.ev.planner.solve;

/**
 * Cost of the preemptive charging relaxation and of the contiguous sessions that were published.
 *
 * <p>The relaxation may split a session across tariff fragments. The published plan does not.
 * {@code relaxationMicros} is therefore a lower bound on {@code achievedMicros}.
 *
 * @param relaxationMicros preemptive slot-flow cost in micro-units
 * @param achievedMicros contiguous session cost in micro-units
 */
public record FlowBound(long relaxationMicros, long achievedMicros) {

  public FlowBound {
    if (relaxationMicros < 0L) {
      throw new IllegalArgumentException(
          "relaxationMicros must be non-negative: " + relaxationMicros);
    }
    if (achievedMicros < 0L) {
      throw new IllegalArgumentException("achievedMicros must be non-negative: " + achievedMicros);
    }
    if (relaxationMicros > achievedMicros) {
      throw new IllegalArgumentException(
          "relaxationMicros ("
              + relaxationMicros
              + ") exceeds achievedMicros ("
              + achievedMicros
              + ")");
    }
  }

  /** Micro-units left on the table because sessions cannot be split. */
  public long gapMicros() {
    return achievedMicros - relaxationMicros;
  }
}
