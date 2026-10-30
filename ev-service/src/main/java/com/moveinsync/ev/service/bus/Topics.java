package com.moveinsync.ev.service.bus;

/**
 * Topic names from the architecture. Keys are {@code tenant:cab}, {@code tenant:hub}, or {@code
 * tenant:shift}.
 */
public final class Topics {

  public static final String TELEMETRY = "ev.telemetry.v1";
  public static final String CHARGER_STATUS = "ev.charger-status.v1";
  public static final String PLAN_TRIGGERS = "ev.plan-triggers.v1";
  public static final String PLAN_EVENTS = "ev.plan-events.v1";

  private Topics() {
    throw new IllegalStateException("utility");
  }

  public static String retryShort(String topic) {
    return topic + ".retry.5s";
  }

  public static String retryLong(String topic) {
    return topic + ".retry.1m";
  }

  public static String deadLetter(String topic) {
    return topic + ".dlq";
  }
}
