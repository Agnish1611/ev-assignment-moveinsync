package com.moveinsync.ev.service.bus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Registers {@code ev/events.proto} with a schema registry. The body is the contract, not a plan.
 */
public final class SchemaPublisher {

  public void publish(String baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    String body = "{\"schemaType\":\"PROTOBUF\",\"schema\":\"" + escape(contract()) + "\"}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/subjects/ev.envelope.v1/versions"))
            .header("Content-Type", "application/vnd.schemaregistry.v1+json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    HttpResponse<String> response = post(request);
    if (response.statusCode() / 100 != 2) {
      throw new BusException("schema registry rejected the contract");
    }
  }

  private static HttpResponse<String> post(HttpRequest request) {
    try {
      return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new BusException("schema registry call was interrupted", ex);
    } catch (IOException ex) {
      throw new BusException("schema registry call failed", ex);
    }
  }

  private static String contract() {
    try (InputStream in = SchemaPublisher.class.getResourceAsStream("/ev/events.proto")) {
      if (in == null) {
        throw new IllegalStateException("event contract is missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("event contract could not be read", ex);
    }
  }

  private static String escape(String schema) {
    StringBuilder out = new StringBuilder();
    for (int index = 0; index < schema.length(); index++) {
      char symbol = schema.charAt(index);
      switch (symbol) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> out.append(symbol);
      }
    }
    return out.toString();
  }
}
