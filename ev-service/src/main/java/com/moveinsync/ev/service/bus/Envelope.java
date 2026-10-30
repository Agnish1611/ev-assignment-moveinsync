package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.planner.solve.DriftEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Versioned event. {@code schemaVersion} starts at 1. A newer field is ignored by this reader, and
 * an older message that omits a later field still parses.
 *
 * @param eventId unique id for this publication
 * @param tenant opaque tenant id
 * @param producer idempotency producer
 * @param sequence idempotency sequence
 * @param eventTime event time in UTC
 * @param schemaVersion envelope schema version
 * @param payload one body
 */
public record Envelope(
    String eventId,
    String tenant,
    String producer,
    long sequence,
    Instant eventTime,
    int schemaVersion,
    Payload payload) {

  public static final int SCHEMA_VERSION = 1;

  public Envelope {
    requireOpaque(eventId, "eventId");
    requireOpaque(tenant, "tenant");
    requireOpaque(producer, "producer");
    if (sequence < 0L) {
      throw new IllegalArgumentException("sequence must be non-negative");
    }
    Objects.requireNonNull(eventTime, "eventTime must not be null");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion is not supported");
    }
    Objects.requireNonNull(payload, "payload must not be null");
  }

  /** One body. Coordinates are not part of the contract. */
  public sealed interface Payload
      permits Telemetry, ChargerStatus, PlanTrigger, PlanPublished, ConfigChanged {}

  /** Cab energy in watt-hours. */
  public record Telemetry(String cabId, long socWh) implements Payload {
    public Telemetry {
      requireOpaque(cabId, "cabId");
      if (socWh < 0L) {
        throw new IllegalArgumentException("socWh must be non-negative");
      }
    }
  }

  /** One plug. {@code operational} false is a charger failure. */
  public record ChargerStatus(String hubId, String chargerId, boolean operational)
      implements Payload {
    public ChargerStatus {
      requireOpaque(hubId, "hubId");
      requireOpaque(chargerId, "chargerId");
    }
  }

  /** A planner trigger. {@code kind} is a {@link DriftEvent.Kind} name. */
  public record PlanTrigger(
      String kind, String subjectId, String shard, Instant observedAt, OptionalLong reportedSocWh)
      implements Payload {
    public PlanTrigger {
      DriftEvent.Kind.valueOf(Objects.requireNonNull(kind, "kind must not be null"));
      requireOpaque(subjectId, "subjectId");
      requireOpaque(shard, "shard");
      Objects.requireNonNull(observedAt, "observedAt must not be null");
      Objects.requireNonNull(reportedSocWh, "reportedSocWh must not be null");
      if (reportedSocWh.isPresent() && reportedSocWh.orElseThrow() < 0L) {
        throw new IllegalArgumentException("reportedSocWh must be non-negative");
      }
    }
  }

  /** A published head version. */
  public record PlanPublished(String shard, long version) implements Payload {
    public PlanPublished {
      requireOpaque(shard, "shard");
      if (version <= 0L) {
        throw new IllegalArgumentException("version must be positive");
      }
    }
  }

  /** A catalog write. The subject is an opaque id, never a coordinate. */
  public record ConfigChanged(String kind, String subjectId) implements Payload {
    public ConfigChanged {
      requireOpaque(kind, "kind");
      requireOpaque(subjectId, "subjectId");
    }
  }

  private static void requireOpaque(String value, String name) {
    if (value == null || value.isBlank() || value.contains("@") || value.startsWith("+")) {
      throw new IllegalArgumentException(name + " must be an opaque identifier");
    }
  }
}
