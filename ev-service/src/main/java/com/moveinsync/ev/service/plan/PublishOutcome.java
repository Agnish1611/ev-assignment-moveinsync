package com.moveinsync.ev.service.plan;

/** What a publication attempt did to the plan head. */
public enum PublishOutcome {
  PUBLISHED,
  REPLAYED,
  CONFLICT,
  REJECTED
}
