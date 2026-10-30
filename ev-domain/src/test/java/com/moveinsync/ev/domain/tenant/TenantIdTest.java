package com.moveinsync.ev.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantIdTest {

  @Test
  @DisplayName("Valid tenant ID is constructed and formatted properly")
  void validTenantId() {
    TenantId tenantId = TenantId.of("tenant-moveinsync-01");
    assertThat(tenantId.value()).isEqualTo("tenant-moveinsync-01");
    assertThat(tenantId.toString()).contains("tenant-moveinsync-01");
  }

  @Test
  @DisplayName("Null or blank tenant ID is rejected")
  void nullOrBlankRejected() {
    assertThatThrownBy(() -> TenantId.of(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> TenantId.of("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TenantId.of("   ")).isInstanceOf(IllegalArgumentException.class);
  }
}
