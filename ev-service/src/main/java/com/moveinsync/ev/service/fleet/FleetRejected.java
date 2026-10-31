package com.moveinsync.ev.service.fleet;

/** A fleet cannot be planned. The message is a stable code and an opaque id. */
public final class FleetRejected extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String code;

  public FleetRejected(String code, String subject) {
    super(code + ":" + subject);
    this.code = code;
  }

  public FleetRejected(String code, String subject, Throwable cause) {
    super(code + ":" + subject, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
