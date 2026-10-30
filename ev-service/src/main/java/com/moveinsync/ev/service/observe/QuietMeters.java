package com.moveinsync.ev.service.observe;

/** Observer used when a caller does not record meters. */
public final class QuietMeters implements PublishObserver, DeliveryObserver, OperationalMeters {

  static final QuietMeters INSTANCE = new QuietMeters();

  private QuietMeters() {
    // Records nothing.
  }
}
