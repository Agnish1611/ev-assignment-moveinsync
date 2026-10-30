package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The registry receives the proto text. A non-success response fails the publish. */
class SchemaPublisherTest {

  @Test
  void theContractIsPostedAsProtobuf() throws IOException {
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> type = new AtomicReference<>();
    HttpServer server = server(200, body, type);
    try {
      new SchemaPublisher().publish("http://127.0.0.1:" + server.getAddress().getPort());
    } finally {
      server.stop(0);
    }

    assertThat(type.get()).isEqualTo("application/vnd.schemaregistry.v1+json");
    assertThat(body.get()).contains("\"schemaType\":\"PROTOBUF\"");
    assertThat(body.get()).contains("event_id");
  }

  @Test
  void aRejectedContractFails() throws IOException {
    HttpServer server = server(409, new AtomicReference<>(), new AtomicReference<>());
    try {
      assertThatThrownBy(
              () ->
                  new SchemaPublisher()
                      .publish("http://127.0.0.1:" + server.getAddress().getPort()))
          .isInstanceOf(BusException.class);
    } finally {
      server.stop(0);
    }
  }

  private static HttpServer server(
      int status, AtomicReference<String> body, AtomicReference<String> type) throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/subjects/ev.envelope.v1/versions",
        exchange -> {
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          type.set(exchange.getRequestHeaders().getFirst("Content-Type"));
          byte[] response = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    return server;
  }
}
