package com.moveinsync.ev.service.plan;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** Signs RS256 tokens and serves the matching JWKS on the loopback interface. */
final class TokenFixture {

  private final RSAKey key;
  private final HttpServer server;

  private TokenFixture(RSAKey key, HttpServer server) {
    this.key = key;
    this.server = server;
  }

  static TokenFixture start() {
    try {
      RSAKey key = new RSAKeyGenerator(2048).keyID("http-test").generate();
      byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      return new TokenFixture(key, server);
    } catch (JOSEException | IOException ex) {
      throw new IllegalStateException("jwks fixture failed", ex);
    }
  }

  String jwkUri() {
    String host = server.getAddress().getAddress().getHostAddress();
    if (host.indexOf(':') >= 0) {
      host = "[" + host + "]";
    }
    return "http://" + host + ":" + server.getAddress().getPort() + "/jwks";
  }

  String token(String tenant, String subject, String role) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .subject(subject)
            .claim("role", role)
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3_600)));
    if (tenant != null) {
      claims.claim("tenant_id", tenant);
    }
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
              claims.build());
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException ex) {
      throw new IllegalStateException("token fixture failed", ex);
    }
  }

  void stop() {
    server.stop(0);
  }
}
