package com.moveinsync.ev.service.api;

/**
 * Which process this JVM is. The web instances are {@code api}. The solver host is {@code worker}.
 */
public final class ProcessRole {

  public static final String API = "'${ev.roles:api}'.contains('api')";

  public static final String WORKER = "'${ev.roles:api}'.contains('worker')";

  private ProcessRole() {
    throw new IllegalStateException("utility");
  }
}
