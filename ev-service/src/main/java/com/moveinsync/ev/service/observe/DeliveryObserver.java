package com.moveinsync.ev.service.observe;

/** What delivery reports. The default records nothing. */
public interface DeliveryObserver {

  default void duplicate() {
    // The default observer records nothing.
  }

  default void late() {
    // The default observer records nothing.
  }

  default void retried() {
    // The default observer records nothing.
  }

  default void deadLettered() {
    // The default observer records nothing.
  }

  static DeliveryObserver none() {
    return QuietMeters.INSTANCE;
  }
}
