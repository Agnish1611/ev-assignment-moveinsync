package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.service.repository.OutboxRepository;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OutboxRelayTest {

  @Test
  void aTriggerKeepsTheTopicAndTheEnvelope() {
    Instant at = Instant.parse("2026-10-30T08:00:00Z");
    byte[] body =
        EnvelopeCodec.write(
            new Envelope(
                "job-1",
                "tenant-a",
                "plan-api",
                1L,
                at,
                Envelope.SCHEMA_VERSION,
                new Envelope.PlanRequested("shard-a", "job-1", Envelope.PlanRequested.INITIAL)));
    OutboxRepository.OutboxRow row =
        new OutboxRepository.OutboxRow(
            4L,
            "tenant-a",
            "shard-a",
            0L,
            at,
            Topics.PLAN_TRIGGERS,
            Base64.getEncoder().encodeToString(body));
    BusRecord record =
        new OutboxRelay(new MemoryMessageBus(), new OutboxRepository(), BusSettings.standard())
            .delivery(row);

    assertThat(record.topic()).isEqualTo(Topics.PLAN_TRIGGERS);
    assertThat(record.value()).isEqualTo(body);
  }
}
