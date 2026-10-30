package com.moveinsync.ev.service.bus;

/** A transport failure. The message names the operation, not the payload. */
public final class BusException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public BusException(String message) {
    super(message);
  }

  public BusException(String message, Throwable cause) {
    super(message, cause);
  }
}
