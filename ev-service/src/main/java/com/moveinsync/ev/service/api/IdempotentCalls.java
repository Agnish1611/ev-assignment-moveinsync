package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.ev.service.repository.IdempotencyRepository;
import com.moveinsync.ev.service.repository.IdempotencyRepository.StoredCall;
import java.time.Clock;
import java.util.Optional;
import org.springframework.http.ResponseEntity;

/** Replays a stored response when the same caller repeats an Idempotency-Key. */
public final class IdempotentCalls {

  private final AppConnections connections;
  private final IdempotencyRepository store;
  private final Clock clock;
  private final int maxIdLength;

  public IdempotentCalls(
      AppConnections connections, IdempotencyRepository store, Clock clock, int maxIdLength) {
    this.connections = connections;
    this.store = store;
    this.clock = clock;
    this.maxIdLength = maxIdLength;
  }

  public <T> ResponseEntity<T> once(
      Caller caller,
      String key,
      String method,
      String path,
      int status,
      Class<T> type,
      AppConnections.SqlWork<T> work) {
    String safeKey = requireKey(key);
    try {
      return attempt(caller, safeKey, method, path, status, type, work);
    } catch (ApiException ex) {
      if (!"CONFLICT".equals(ex.code())) {
        throw ex;
      }
      return replay(caller, safeKey, method, path, type);
    }
  }

  private <T> ResponseEntity<T> attempt(
      Caller caller,
      String key,
      String method,
      String path,
      int status,
      Class<T> type,
      AppConnections.SqlWork<T> work) {
    return connections.change(
        caller.tenant(),
        connection -> {
          Optional<StoredCall> prior = store.find(connection, caller.userId(), key);
          if (prior.isPresent()) {
            return replay(prior.orElseThrow(), method, path, type);
          }
          T created = work.apply(connection);
          store.save(
              connection,
              caller.tenant(),
              caller.userId(),
              key,
              method,
              path,
              status,
              json(created),
              clock.instant());
          return ResponseEntity.status(status).body(created);
        });
  }

  private <T> ResponseEntity<T> replay(
      Caller caller, String key, String method, String path, Class<T> type) {
    return connections.read(
        caller.tenant(),
        connection -> {
          Optional<StoredCall> prior = store.find(connection, caller.userId(), key);
          if (prior.isEmpty()) {
            throw new ApiException(409, "CONFLICT", "The resource already exists");
          }
          return replay(prior.orElseThrow(), method, path, type);
        });
  }

  private <T> ResponseEntity<T> replay(
      StoredCall stored, String method, String path, Class<T> type) {
    if (!stored.method().equals(method) || !stored.path().equals(path)) {
      throw new ApiException(
          409, "IDEMPOTENCY_CONFLICT", "Idempotency-Key was used for another request");
    }
    return ResponseEntity.status(stored.status()).body(read(stored.body(), type));
  }

  private String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
    }
    return OpaqueIds.require(key, maxIdLength);
  }

  private String json(Object body) {
    try {
      return new ObjectMapper().writeValueAsString(body);
    } catch (JsonProcessingException ex) {
      throw new ApiException(500, "STORE_FAILED", "The request could not be stored");
    }
  }

  private <T> T read(String body, Class<T> type) {
    try {
      return new ObjectMapper().readValue(body, type);
    } catch (JsonProcessingException ex) {
      throw new ApiException(500, "STORE_FAILED", "The request could not be stored");
    }
  }
}
