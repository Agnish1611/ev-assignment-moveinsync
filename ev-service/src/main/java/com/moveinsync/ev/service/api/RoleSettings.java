package com.moveinsync.ev.service.api;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Process roles. {@code api} serves HTTP. {@code worker} relays and will solve. {@code detector} is
 * reserved.
 */
@ConfigurationProperties(prefix = "ev")
public record RoleSettings(@DefaultValue("api") List<String> roles) {

  private static final Set<String> ALLOWED = Set.of("api", "worker", "detector");

  public RoleSettings {
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("ev.roles must name at least one role");
    }
    for (String role : roles) {
      if (role == null || !ALLOWED.contains(role)) {
        throw new IllegalArgumentException("ev.roles has an unknown role");
      }
    }
    roles = List.copyOf(roles);
  }

  public boolean api() {
    return roles.contains("api");
  }

  public boolean worker() {
    return roles.contains("worker");
  }
}
