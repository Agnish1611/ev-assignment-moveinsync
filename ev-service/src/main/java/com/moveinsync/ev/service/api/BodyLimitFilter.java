package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects a declared body that is larger than the configured limit. */
final class BodyLimitFilter extends OncePerRequestFilter {

  private final int maxBodyBytes;
  private final ObjectMapper mapper;

  BodyLimitFilter(int maxBodyBytes, ObjectMapper mapper) {
    this.maxBodyBytes = maxBodyBytes;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxBodyBytes) {
      ProblemWriter.write(
          mapper, request, response, 413, "BODY_TOO_LARGE", "The body is too large");
      return;
    }
    chain.doFilter(request, response);
  }
}
