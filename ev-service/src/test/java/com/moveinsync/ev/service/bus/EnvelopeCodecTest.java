package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** The codec and {@code ev/events.proto} use the same field numbers. */
class EnvelopeCodecTest {

  private static final Instant AT = Instant.parse("2026-10-30T08:00:01.000000123Z");

  @Test
  void aRoundTripKeepsEveryPayload() {
    Envelope telemetry = envelope(1L, new Envelope.Telemetry("CAB-1", 13_200L));
    Envelope charger = envelope(2L, new Envelope.ChargerStatus("HUB-1", "CHG-1", false));
    Envelope trigger =
        envelope(
            3L,
            new Envelope.PlanTrigger("BATTERY", "CAB-1", "shift-1", AT, OptionalLong.of(13_200L)));
    Envelope published = envelope(4L, new Envelope.PlanPublished("shift-1", 8L));
    Envelope changed = envelope(5L, new Envelope.ConfigChanged("HUB", "HUB-1"));

    assertThat(EnvelopeCodec.read(EnvelopeCodec.write(telemetry))).isEqualTo(telemetry);
    assertThat(EnvelopeCodec.read(EnvelopeCodec.write(charger))).isEqualTo(charger);
    assertThat(EnvelopeCodec.read(EnvelopeCodec.write(trigger))).isEqualTo(trigger);
    assertThat(EnvelopeCodec.read(EnvelopeCodec.write(published))).isEqualTo(published);
    assertThat(EnvelopeCodec.read(EnvelopeCodec.write(changed))).isEqualTo(changed);
  }

  @Test
  void anUnknownFieldIsSkipped() throws IOException {
    byte[] message = EnvelopeCodec.write(envelope(1L, new Envelope.Telemetry("CAB-1", 100L)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.writeBytes(message);
    CodedOutputStream out = CodedOutputStream.newInstance(bytes);
    out.writeInt32(30, 7);
    out.flush();

    Envelope read = EnvelopeCodec.read(bytes.toByteArray());

    assertThat(read.sequence()).isEqualTo(1L);
    assertThat(read.payload()).isEqualTo(new Envelope.Telemetry("CAB-1", 100L));
  }

  @Test
  void aMissingPayloadOrOldSchemaIsRejected() {
    assertThatThrownBy(() -> EnvelopeCodec.read(new byte[] {0x08, 0x01}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Envelope(
                    "id", "tenant-a", "device", 0L, AT, 0, new Envelope.Telemetry("CAB-1", 1L)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theProtoDeclaresTheCodecFieldNumbers() throws IOException {
    try (InputStream in = EnvelopeCodecTest.class.getResourceAsStream("/ev/events.proto")) {
      String proto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(proto).contains("string event_id = 1;");
      assertThat(proto).contains("string tenant = 2;");
      assertThat(proto).contains("string producer = 3;");
      assertThat(proto).contains("int64 seq = 4;");
      assertThat(proto).contains("int64 event_time_seconds = 5;");
      assertThat(proto).contains("int32 schema_version = 6;");
      assertThat(proto).contains("int32 event_time_nanos = 7;");
      assertThat(proto).contains("Telemetry telemetry = 10;");
      assertThat(proto).contains("ChargerStatus charger_status = 11;");
      assertThat(proto).contains("PlanTrigger plan_trigger = 12;");
      assertThat(proto).contains("PlanPublished plan_published = 13;");
      assertThat(proto).contains("string cab_id = 1;");
      assertThat(proto).contains("int64 soc_wh = 2;");
    }
  }

  private static Envelope envelope(long sequence, Envelope.Payload payload) {
    return new Envelope(
        "tenant-a:device:" + sequence,
        "tenant-a",
        "device",
        sequence,
        AT,
        Envelope.SCHEMA_VERSION,
        payload);
  }
}
