package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Charges one token to the authenticated subject. */
final class RateLimitFilter extends OncePerRequestFilter {

  private final RateGate gate;
  private final ObjectMapper mapper;

  RateLimitFilter(RateGate gate, ObjectMapper mapper) {
    this.gate = gate;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken token) {
      String subject = token.getToken().getSubject();
      if (subject != null && !gate.allow(subject)) {
        ProblemWriter.write(mapper, request, response, 429, "RATE_LIMITED", "Too many requests");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
