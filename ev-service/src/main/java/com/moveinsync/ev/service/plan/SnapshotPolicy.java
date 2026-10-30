package com.moveinsync.ev.service.plan;

import java.time.Instant;
import java.util.Optional;

/**
 * When a published version stores a full plan. Version 1 and every 50th version are snapshots, and
 * so is any version published 10 minutes after the previous snapshot.
 */
final class SnapshotPolicy {

  static final int EVERY_VERSIONS = 50;
  static final long MAX_AGE_SECONDS = 600L;

  private SnapshotPolicy() {
    throw new IllegalStateException("utility");
  }

  static boolean due(long version, Optional<Instant> lastSnapshotAt, Instant now) {
    if (version <= 0L) {
      throw new IllegalArgumentException("version must be positive");
    }
    if (version == 1L || version % EVERY_VERSIONS == 0L) {
      return true;
    }
    if (lastSnapshotAt.isEmpty()) {
      return true;
    }
    Instant last = lastSnapshotAt.orElseThrow();
    return !now.isBefore(last.plusSeconds(MAX_AGE_SECONDS));
  }
}
