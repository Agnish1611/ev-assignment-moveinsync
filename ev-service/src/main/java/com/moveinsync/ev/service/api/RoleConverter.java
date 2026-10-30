package com.moveinsync.ev.service.api;

import java.util.List;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Maps the allow-listed {@code role} claim. Any other value grants nothing. */
public final class RoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  static final Set<String> ROLES = Set.of("EMPLOYEE", "OPERATOR", "ADMIN", "DEVICE");

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    String role = jwt.getClaimAsString("role");
    if (role != null && ROLES.contains(role)) {
      return new JwtAuthenticationToken(
          jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), jwt.getSubject());
    }
    return new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject());
  }
}
