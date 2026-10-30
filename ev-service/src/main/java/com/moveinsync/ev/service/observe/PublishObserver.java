package com.moveinsync.ev.service.observe;

/** What publication reports. The default records nothing. */
public interface PublishObserver {

  default void rejected() {
    // The default observer records nothing.
  }

  default void conflict() {
    // The default observer records nothing.
  }

  default void published(PublishedSample sample) {
    // The default observer records nothing.
  }

  static PublishObserver none() {
    return QuietMeters.INSTANCE;
  }
}
