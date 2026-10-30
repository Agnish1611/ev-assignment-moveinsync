package com.moveinsync.ev.service.bus;

/**
 * Timeouts and pool bounds for the bus. The poll interval stays above the longest solve so a paused
 * partition does not force a rebalance.
 *
 * @param solveThreads worker threads, separate from the poll thread
 * @param solveQueue bounded queue; a full queue rejects instead of running on the poll thread
 * @param maxPollIntervalMillis Kafka {@code max.poll.interval.ms}
 * @param longestSolveMillis the longest solve the interval must outlast
 * @param retryShortSeconds first retry delay
 * @param retryLongSeconds second retry delay
 * @param pollMillis poll wait while a partition is paused
 * @param sendTimeoutMillis producer ack wait
 * @param outboxBatch rows claimed per relay poll
 */
public record BusSettings(
    int solveThreads,
    int solveQueue,
    long maxPollIntervalMillis,
    long longestSolveMillis,
    long retryShortSeconds,
    long retryLongSeconds,
    long pollMillis,
    long sendTimeoutMillis,
    int outboxBatch) {

  public BusSettings {
    if (solveThreads <= 0 || solveQueue <= 0 || outboxBatch <= 0) {
      throw new IllegalArgumentException("pool sizes must be positive");
    }
    if (retryShortSeconds <= 0L || retryLongSeconds <= 0L) {
      throw new IllegalArgumentException("retry delays must be positive");
    }
    if (pollMillis <= 0L || sendTimeoutMillis <= 0L || longestSolveMillis <= 0L) {
      throw new IllegalArgumentException("timeouts must be positive");
    }
    if (maxPollIntervalMillis <= longestSolveMillis) {
      throw new IllegalArgumentException("max.poll.interval.ms must outlast the longest solve");
    }
  }

  public static BusSettings standard() {
    return new BusSettings(2, 8, 120_000L, 30_000L, 5L, 60L, 200L, 10_000L, 8);
  }
}
