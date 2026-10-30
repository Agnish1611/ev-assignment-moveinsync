package com.moveinsync.ev.domain.tenant;

import java.util.Objects;

/**
 * Immutable value object representing a tenant identifier.
 *
 * <p>Enforces tenant isolation in compliance with AGENTS.md Section 4.1 and Decision D3.
 *
 * @param value non-null, non-blank tenant identifier string
 */
public record TenantId(String value) {

  public TenantId {
    Objects.requireNonNull(value, "TenantId value must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("TenantId value must not be blank");
    }
  }

  /**
   * Factory method to create a valid {@link TenantId}.
   *
   * @param value tenant identifier
   * @return validated TenantId instance
   */
  public static TenantId of(String value) {
    return new TenantId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
