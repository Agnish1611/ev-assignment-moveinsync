package com.moveinsync.ev.service.fault;

/** The gate is open. The caller fails without waiting on the database or the broker. */
public final class CallBlocked extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CallBlocked() {
    super("circuit open");
  }
}
