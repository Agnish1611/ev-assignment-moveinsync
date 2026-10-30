package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless resource server. The tenant is a claim, never a request field. */
@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication
public class ApiSecurity {

  @Bean
  SecurityFilterChain apiChain(
      HttpSecurity http, ObjectMapper mapper, ApiSettings settings, RateGate gate)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers("/v1/**").authenticated().anyRequest().denyAll())
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationEntryPoint(
                        (request, response, ex) -> unauthorized(mapper, request, response))
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(new RoleConverter())))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, ex) -> unauthorized(mapper, request, response))
                    .accessDeniedHandler(
                        (request, response, ex) -> forbidden(mapper, request, response)))
        .addFilterBefore(new CorrelationFilter(), BearerTokenAuthenticationFilter.class)
        .addFilterBefore(
            new BodyLimitFilter(settings.maxBodyBytes(), mapper),
            BearerTokenAuthenticationFilter.class)
        .addFilterAfter(new RateLimitFilter(gate, mapper), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  private static void unauthorized(
      ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ProblemWriter.write(
        mapper, request, response, 401, "UNAUTHORIZED", "Authentication is required");
  }

  private static void forbidden(
      ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ProblemWriter.write(mapper, request, response, 403, "FORBIDDEN", "The role is not allowed");
  }
}
