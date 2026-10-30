package com.moveinsync.ev.sim;

/**
 * Deterministic integer draws for a scenario seed. Time O(1) per draw. The multiplier is the same
 * one used by {@code java.util.Random}, masked to 48 bits. Remainder selection is slightly biased
 * and is only used to pick fixtures.
 */
final class SeededSequence {

  private static final long MULTIPLIER = 0x5DEECE66DL;
  private static final long ADDEND = 0xBL;
  private static final long MASK = (1L << 48) - 1L;

  private long state;

  SeededSequence(long seed) {
    state = (seed ^ MULTIPLIER) & MASK;
  }

  int nextInt(int bound) {
    if (bound <= 0) {
      throw new IllegalArgumentException("bound must be positive: " + bound);
    }
    state = (state * MULTIPLIER + ADDEND) & MASK;
    int positive = (int) (state >>> 17);
    return positive % bound;
  }
}
