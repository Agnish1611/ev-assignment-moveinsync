package com.moveinsync.ev.service.observe;

/** Counters the web process can raise without publishing a plan. */
public interface OperationalMeters {

  default void rejectedEvent() {
    // The default observer records nothing.
  }

  default void deadlineMiss() {
    // The default observer records nothing.
  }
}
