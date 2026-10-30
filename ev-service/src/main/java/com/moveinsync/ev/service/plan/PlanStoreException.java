package com.moveinsync.ev.service.plan;

/** A plan could not be stored. The message is stable and does not include SQL text. */
public final class PlanStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PlanStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
