package com.moveinsync.ev.service.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;

/** A correlation id is an opaque token. An email in the header is discarded. */
public final class Correlation {

  public static final String HEADER = "X-Correlation-Id";
  public static final String ATTRIBUTE = "ev.correlation";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private Correlation() {
    throw new IllegalStateException("utility");
  }

  public static String resolve(HttpServletRequest request) {
    Object existing = request.getAttribute(ATTRIBUTE);
    if (existing instanceof String saved) {
      return saved;
    }
    String supplied = request.getHeader(HEADER);
    String value =
        supplied != null && SAFE.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute(ATTRIBUTE, value);
    return value;
  }
}
