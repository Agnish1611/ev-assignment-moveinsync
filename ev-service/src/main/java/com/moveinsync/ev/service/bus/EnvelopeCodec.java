package com.moveinsync.ev.service.bus;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/** Protobuf wire format for {@link Envelope}. Field numbers match {@code ev/events.proto}. */
public final class EnvelopeCodec {

  private static final int EVENT_ID = 1;
  private static final int TENANT = 2;
  private static final int PRODUCER = 3;
  private static final int SEQUENCE = 4;
  private static final int EVENT_SECONDS = 5;
  private static final int SCHEMA = 6;
  private static final int EVENT_NANOS = 7;
  private static final int TELEMETRY = 10;
  private static final int CHARGER = 11;
  private static final int TRIGGER = 12;
  private static final int PUBLISHED = 13;
  private static final int CONFIG = 14;

  private EnvelopeCodec() {
    throw new IllegalStateException("utility");
  }

  public static byte[] write(Envelope envelope) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    try {
      out.writeString(EVENT_ID, envelope.eventId());
      out.writeString(TENANT, envelope.tenant());
      out.writeString(PRODUCER, envelope.producer());
      out.writeInt64(SEQUENCE, envelope.sequence());
      Duration eventTime = Duration.between(Instant.EPOCH, envelope.eventTime());
      out.writeInt64(EVENT_SECONDS, eventTime.getSeconds());
      out.writeInt32(SCHEMA, envelope.schemaVersion());
      out.writeInt32(EVENT_NANOS, eventTime.getNano());
      writePayload(out, envelope.payload());
      out.flush();
    } catch (IOException ex) {
      throw new IllegalArgumentException("event could not be stored", ex);
    }
    return bytes.toByteArray();
  }

  public static Envelope read(byte[] bytes) {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String eventId = "";
    String tenant = "";
    String producer = "";
    long sequence = 0L;
    long seconds = 0L;
    int nanos = 0;
    int schema = 0;
    Envelope.Payload payload = null;
    try {
      while (!in.isAtEnd()) {
        int tag = in.readTag();
        int field = WireFormat.getTagFieldNumber(tag);
        switch (field) {
          case EVENT_ID -> eventId = in.readStringRequireUtf8();
          case TENANT -> tenant = in.readStringRequireUtf8();
          case PRODUCER -> producer = in.readStringRequireUtf8();
          case SEQUENCE -> sequence = in.readInt64();
          case EVENT_SECONDS -> seconds = in.readInt64();
          case SCHEMA -> schema = in.readInt32();
          case EVENT_NANOS -> nanos = in.readInt32();
          case TELEMETRY -> payload = readTelemetry(in.readByteArray());
          case CHARGER -> payload = readCharger(in.readByteArray());
          case TRIGGER -> payload = readTrigger(in.readByteArray());
          case PUBLISHED -> payload = readPublished(in.readByteArray());
          case CONFIG -> payload = readConfig(in.readByteArray());
          default -> in.skipField(tag);
        }
      }
    } catch (IOException ex) {
      throw new IllegalArgumentException("event could not be read", ex);
    }
    if (payload == null) {
      throw new IllegalArgumentException("event payload is missing");
    }
    return new Envelope(
        eventId,
        tenant,
        producer,
        sequence,
        Instant.ofEpochSecond(seconds, nanos),
        schema,
        payload);
  }

  private static void writePayload(CodedOutputStream out, Envelope.Payload payload)
      throws IOException {
    switch (payload) {
      case Envelope.Telemetry telemetry -> out.writeByteArray(TELEMETRY, telemetryBytes(telemetry));
      case Envelope.ChargerStatus charger -> out.writeByteArray(CHARGER, chargerBytes(charger));
      case Envelope.PlanTrigger trigger -> out.writeByteArray(TRIGGER, triggerBytes(trigger));
      case Envelope.PlanPublished published ->
          out.writeByteArray(PUBLISHED, publishedBytes(published));
      case Envelope.ConfigChanged changed -> out.writeByteArray(CONFIG, configBytes(changed));
    }
  }

  private static byte[] telemetryBytes(Envelope.Telemetry telemetry) throws IOException {
    return nested(
        out -> {
          out.writeString(1, telemetry.cabId());
          out.writeInt64(2, telemetry.socWh());
        });
  }

  private static byte[] chargerBytes(Envelope.ChargerStatus charger) throws IOException {
    return nested(
        out -> {
          out.writeString(1, charger.hubId());
          out.writeString(2, charger.chargerId());
          out.writeBool(3, charger.operational());
        });
  }

  private static byte[] triggerBytes(Envelope.PlanTrigger trigger) throws IOException {
    return nested(
        out -> {
          out.writeString(1, trigger.kind());
          out.writeString(2, trigger.subjectId());
          Duration observed = Duration.between(Instant.EPOCH, trigger.observedAt());
          out.writeInt64(3, observed.getSeconds());
          out.writeInt32(4, observed.getNano());
          out.writeBool(5, trigger.reportedSocWh().isPresent());
          if (trigger.reportedSocWh().isPresent()) {
            out.writeInt64(6, trigger.reportedSocWh().orElseThrow());
          }
          out.writeString(7, trigger.shard());
        });
  }

  private static byte[] publishedBytes(Envelope.PlanPublished published) throws IOException {
    return nested(
        out -> {
          out.writeString(1, published.shard());
          out.writeInt64(2, published.version());
        });
  }

  private static Envelope.Telemetry readTelemetry(byte[] bytes) throws IOException {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String cab = "";
    long soc = 0L;
    while (!in.isAtEnd()) {
      int tag = in.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> cab = in.readStringRequireUtf8();
        case 2 -> soc = in.readInt64();
        default -> in.skipField(tag);
      }
    }
    return new Envelope.Telemetry(cab, soc);
  }

  private static Envelope.ChargerStatus readCharger(byte[] bytes) throws IOException {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String hub = "";
    String charger = "";
    boolean operational = false;
    while (!in.isAtEnd()) {
      int tag = in.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> hub = in.readStringRequireUtf8();
        case 2 -> charger = in.readStringRequireUtf8();
        case 3 -> operational = in.readBool();
        default -> in.skipField(tag);
      }
    }
    return new Envelope.ChargerStatus(hub, charger, operational);
  }

  private static Envelope.PlanTrigger readTrigger(byte[] bytes) throws IOException {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String kind = "";
    String subject = "";
    String shard = "";
    long seconds = 0L;
    int nanos = 0;
    boolean hasSoc = false;
    long soc = 0L;
    while (!in.isAtEnd()) {
      int tag = in.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> kind = in.readStringRequireUtf8();
        case 2 -> subject = in.readStringRequireUtf8();
        case 3 -> seconds = in.readInt64();
        case 4 -> nanos = in.readInt32();
        case 5 -> hasSoc = in.readBool();
        case 6 -> soc = in.readInt64();
        case 7 -> shard = in.readStringRequireUtf8();
        default -> in.skipField(tag);
      }
    }
    OptionalLong reported = hasSoc ? OptionalLong.of(soc) : OptionalLong.empty();
    return new Envelope.PlanTrigger(
        kind, subject, shard, Instant.ofEpochSecond(seconds, nanos), reported);
  }

  private static byte[] configBytes(Envelope.ConfigChanged changed) throws IOException {
    return nested(
        out -> {
          out.writeString(1, changed.kind());
          out.writeString(2, changed.subjectId());
        });
  }

  private static Envelope.ConfigChanged readConfig(byte[] bytes) throws IOException {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String kind = "";
    String subject = "";
    while (!in.isAtEnd()) {
      int tag = in.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> kind = in.readStringRequireUtf8();
        case 2 -> subject = in.readStringRequireUtf8();
        default -> in.skipField(tag);
      }
    }
    return new Envelope.ConfigChanged(kind, subject);
  }

  private static Envelope.PlanPublished readPublished(byte[] bytes) throws IOException {
    CodedInputStream in = CodedInputStream.newInstance(bytes);
    String shard = "";
    long version = 0L;
    while (!in.isAtEnd()) {
      int tag = in.readTag();
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> shard = in.readStringRequireUtf8();
        case 2 -> version = in.readInt64();
        default -> in.skipField(tag);
      }
    }
    return new Envelope.PlanPublished(shard, version);
  }

  private static byte[] nested(FieldWriter writer) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    writer.write(out);
    out.flush();
    return bytes.toByteArray();
  }

  @FunctionalInterface
  private interface FieldWriter {
    void write(CodedOutputStream out) throws IOException;
  }
}
