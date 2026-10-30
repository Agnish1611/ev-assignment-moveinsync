package com.moveinsync.ev.service.api;

import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.service.api.ApiModels.Accepted;
import com.moveinsync.ev.service.api.ApiModels.BatteryRequest;
import com.moveinsync.ev.service.api.ApiModels.ChargerFailRequest;
import com.moveinsync.ev.service.api.ApiModels.ReplanRequest;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.TelemetryGateway;
import com.moveinsync.ev.service.bus.Topics;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Accepts operational events onto the message bus. It does not solve. */
public final class IngressService {

  private static final int ACCEPTED = HttpStatus.ACCEPTED.value();
  private static final Accepted BODY = new Accepted("ACCEPTED");

  private final IdempotentCalls calls;
  private final MessageBus bus;
  private final Clock clock;
  private final int maxIdLength;

  public IngressService(IdempotentCalls calls, MessageBus bus, Clock clock, int maxIdLength) {
    this.calls = calls;
    this.bus = bus;
    this.clock = clock;
    this.maxIdLength = maxIdLength;
  }

  public ResponseEntity<Accepted> battery(Caller caller, String key, BatteryRequest request) {
    OpaqueIds.require(request.cabId(), maxIdLength);
    if (request.socWh() < 0L || request.sequence() < 0L) {
      throw new ApiException(400, "INVALID_READING", "The reading is not valid");
    }
    Instant observed = CatalogRules.instant(request.observedAt());
    return accept(
        caller,
        key,
        "/v1/events/battery",
        connection -> {
          gateway(caller)
              .publishReading(request.cabId(), request.socWh(), observed, request.sequence());
          return BODY;
        });
  }

  public ResponseEntity<Accepted> chargerFailure(
      Caller caller, String key, String chargerId, ChargerFailRequest request) {
    OpaqueIds.require(chargerId, maxIdLength);
    OpaqueIds.require(request.hubId(), maxIdLength);
    OpaqueIds.require(request.shard(), maxIdLength);
    if (request.sequence() < 0L) {
      throw new ApiException(400, "INVALID_READING", "The reading is not valid");
    }
    Instant observed = clock.instant();
    return accept(
        caller,
        key,
        "/v1/chargers/" + chargerId + "/fail",
        connection -> {
          gateway(caller)
              .publishCharger(
                  request.hubId(), chargerId, false, request.shard(), observed, request.sequence());
          return BODY;
        });
  }

  public ResponseEntity<Accepted> replan(Caller caller, String key, ReplanRequest request) {
    OpaqueIds.require(request.shard(), maxIdLength);
    OpaqueIds.require(request.subjectId(), maxIdLength);
    if (request.sequence() < 0L) {
      throw new ApiException(400, "INVALID_READING", "The reading is not valid");
    }
    DriftEvent.Kind kind = kind(request.kind());
    Instant observed = clock.instant();
    return accept(
        caller,
        key,
        "/v1/replans",
        connection -> {
          sendTrigger(caller, kind, request, observed);
          return BODY;
        });
  }

  private ResponseEntity<Accepted> accept(
      Caller caller, String key, String path, AppConnections.SqlWork<Accepted> work) {
    return calls.once(caller, key, "POST", path, ACCEPTED, Accepted.class, work);
  }

  private TelemetryGateway gateway(Caller caller) {
    return new TelemetryGateway(bus, caller.tenant(), caller.userId());
  }

  private void sendTrigger(
      Caller caller, DriftEvent.Kind kind, ReplanRequest request, Instant observed) {
    Envelope envelope =
        new Envelope(
            caller.tenant() + ":" + caller.userId() + ":" + request.sequence(),
            caller.tenant(),
            caller.userId(),
            request.sequence(),
            observed,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger(
                kind.name(), request.subjectId(), request.shard(), observed, OptionalLong.empty()));
    bus.send(
        BusRecord.of(
            Topics.PLAN_TRIGGERS,
            caller.tenant() + ":" + request.shard(),
            EnvelopeCodec.write(envelope)));
  }

  private static DriftEvent.Kind kind(String value) {
    if (value == null) {
      throw new ApiException(400, "KIND_UNKNOWN", "The event kind is not supported");
    }
    try {
      return DriftEvent.Kind.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(400, "KIND_UNKNOWN", "The event kind is not supported");
    }
  }
}
