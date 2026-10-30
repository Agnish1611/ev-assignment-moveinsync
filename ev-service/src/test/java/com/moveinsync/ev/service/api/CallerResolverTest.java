package com.moveinsync.ev.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The tenant and the user come from the token. A missing claim is rejected. */
class CallerResolverTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anOperatorTokenBecomesTheCaller() {
    authenticate("tenant-a", "op-1", "OPERATOR");

    Caller caller = new CallerResolver(64).require();

    assertThat(caller.tenant()).isEqualTo("tenant-a");
    assertThat(caller.userId()).isEqualTo("op-1");
    assertThat(caller.role()).isEqualTo("OPERATOR");
  }

  @Test
  void aMissingTenantIsUnauthorized() {
    authenticate(null, "op-1", "OPERATOR");

    assertThatThrownBy(() -> new CallerResolver(64).require())
        .isInstanceOfSatisfying(
            ApiException.class, failure -> assertThat(failure.code()).isEqualTo("UNAUTHORIZED"));
  }

  @Test
  void anEmailSubjectIsUnauthorized() {
    authenticate("tenant-a", "a@b.com", "OPERATOR");

    assertThatThrownBy(() -> new CallerResolver(64).require())
        .isInstanceOfSatisfying(
            ApiException.class, failure -> assertThat(failure.code()).isEqualTo("UNAUTHORIZED"));
  }

  private static void authenticate(String tenant, String subject, String role) {
    Jwt.Builder builder =
        Jwt.withTokenValue("token").header("alg", "none").subject(subject).claim("role", role);
    if (tenant != null) {
      builder.claim("tenant_id", tenant);
    }
    Jwt jwt = builder.build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(jwt, new RoleConverter().convert(jwt).getAuthorities()));
  }
}
