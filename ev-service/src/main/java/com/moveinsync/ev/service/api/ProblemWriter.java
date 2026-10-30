package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes one problem body. The mapper is not given the exception. */
public final class ProblemWriter {

  private ProblemWriter() {
    throw new IllegalStateException("utility");
  }

  public static void write(
      ObjectMapper mapper,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String title)
      throws IOException {
    String correlation = Correlation.resolve(request);
    byte[] body =
        mapper.writeValueAsBytes(new Problem("about:blank", title, status, code, correlation));
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader(Correlation.HEADER, correlation);
    response.getOutputStream().write(body);
  }
}
