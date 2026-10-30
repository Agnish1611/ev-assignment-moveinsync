package com.moveinsync.ev.service.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads the tenant and the user from the authenticated token. */
public final class CallerResolver {

  private final int maxIdLength;

  public CallerResolver(int maxIdLength) {
    this.maxIdLength = maxIdLength;
  }

  public Caller require() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      throw new ApiException(401, "UNAUTHORIZED", "Authentication is required");
    }
    Jwt jwt = token.getToken();
    String tenant = authenticated(jwt.getClaimAsString("tenant_id"));
    String userId = authenticated(jwt.getSubject());
    String role = jwt.getClaimAsString("role");
    if (role == null || !RoleConverter.ROLES.contains(role)) {
      throw new ApiException(403, "FORBIDDEN", "The role is not allowed");
    }
    return new Caller(tenant, userId, role);
  }

  private String authenticated(String value) {
    try {
      return OpaqueIds.require(value, maxIdLength);
    } catch (ApiException ex) {
      throw new ApiException(401, "UNAUTHORIZED", "Authentication is required");
    }
  }
}
