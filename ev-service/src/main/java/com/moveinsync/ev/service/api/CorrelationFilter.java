package com.moveinsync.ev.service.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns a correlation id before authentication can fail. */
final class CorrelationFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String correlation = Correlation.resolve(request);
    response.setHeader(Correlation.HEADER, correlation);
    chain.doFilter(request, response);
  }
}
