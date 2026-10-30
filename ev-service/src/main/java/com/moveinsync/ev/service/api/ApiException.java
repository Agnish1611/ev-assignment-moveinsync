package com.moveinsync.ev.service.api;

/** A request the API will not complete. The title is stable and contains no request data. */
public final class ApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final String title;

  public ApiException(int status, String code, String title) {
    super(code);
    this.status = status;
    this.code = code;
    this.title = title;
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
}
