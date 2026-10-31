package com.moveinsync.ev.service.api;

/** A request the API will not complete. The title is stable and contains no request data. */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String title;
  private final int retryAfterSeconds;

  public ApiException(int status, String code, String title) {
    this(status, code, title, 0);
  }

  public ApiException(int status, String code, String title, int retryAfterSeconds) {
    super(code);
    if (retryAfterSeconds < 0) {
      throw new IllegalArgumentException("retry delay must not be negative");
    }
    this.status = status;
    this.code = code;
    this.title = title;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String title() {
    return title;
  }

  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
