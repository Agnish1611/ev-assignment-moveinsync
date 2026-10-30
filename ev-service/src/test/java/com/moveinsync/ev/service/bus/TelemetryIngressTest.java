package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.telemetry.TelemetryGate;
import com.moveinsync.ev.planner.telemetry.TelemetryPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A 22 percent reading becomes one trigger. A wild jump and a replay do not. */
class TelemetryIngressTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final long EXPECTED = 24_600L;

  @Test
  void aReadingFarFromThePlanPublishesOneTrigger() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = subscribe(bus);
    detector(bus).start();

    gateway(bus).publishReading("CAB-1", 13_200L, START, 1L);

    assertThat(triggers).hasSize(1);
    DriftEvent event = PlannerWorker.drift(triggers.get(0));
    assertThat(event.kind()).isEqualTo(DriftEvent.Kind.BATTERY);
    assertThat(event.reportedSocWh()).hasValue(13_200L);
  }

  @Test
  void aQuarantinedJumpDoesNotPublish() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = subscribe(bus);
    detector(bus).start();
    TelemetryGateway gateway = gateway(bus);

    gateway.publishReading("CAB-1", EXPECTED, START, 1L);
    gateway.publishReading("CAB-1", 40_000L, START.plusSeconds(1), 2L);

    assertThat(triggers).isEmpty();
  }

  @Test
  void aRepeatedSequenceDoesNotSolveTwice() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = new ArrayList<>();
    Delivery delivery =
        new Delivery(
            bus, new SeenSequences(), Clock.fixed(START, ZoneOffset.UTC), BusSettings.standard());
    bus.subscribe(
        Topics.PLAN_TRIGGERS,
        (record, ack) -> {
          delivery.attempt(record, triggers::add);
          ack.run();
        });
    detector(bus).start();
    TelemetryGateway gateway = gateway(bus);

    gateway.publishReading("CAB-1", 13_200L, START, 1L);
    gateway.publishReading("CAB-1", 12_000L, START.plusSeconds(400), 1L);

    assertThat(triggers).hasSize(1);
  }

  @Test
  void aFailedChargerPublishesATrigger() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = subscribe(bus);

    gateway(bus).publishCharger("HUB-1", "CHG-1", false, "shift-1", START, 4L);
    gateway(bus).publishCharger("HUB-1", "CHG-1", true, "shift-1", START, 5L);

    assertThat(triggers).hasSize(1);
    assertThat(PlannerWorker.drift(triggers.get(0)).kind())
        .isEqualTo(DriftEvent.Kind.CHARGER_FAILURE);
    assertThat(PlannerWorker.drift(triggers.get(0)).subjectId()).isEqualTo("CHG-1");
  }

  @Test
  void silencePublishesTheDecayedEstimate() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = subscribe(bus);
    DriftDetector detector = detector(bus);
    detector.start();
    gateway(bus).publishReading("CAB-1", EXPECTED, START, 1L);

    detector.silence(START.plusSeconds(120));

    assertThat(triggers).hasSize(1);
    assertThat(PlannerWorker.drift(triggers.get(0)).reportedSocWh()).hasValue(23_400L);
  }

  @Test
  void anotherTenantIsIgnored() {
    MemoryMessageBus bus = new MemoryMessageBus();
    List<Envelope> triggers = subscribe(bus);
    detector(bus).start();

    new TelemetryGateway(bus, "tenant-b", "device-9").publishReading("CAB-9", 1_000L, START, 1L);

    assertThat(triggers).isEmpty();
  }

  private static List<Envelope> subscribe(MemoryMessageBus bus) {
    List<Envelope> triggers = new ArrayList<>();
    bus.subscribe(
        Topics.PLAN_TRIGGERS,
        (record, ack) -> {
          triggers.add(EnvelopeCodec.read(record.value()));
          ack.run();
        });
    return triggers;
  }

  private static DriftDetector detector(MemoryMessageBus bus) {
    return new DriftDetector(
        bus,
        new TelemetryGate(TelemetryPolicy.standard()),
        cabId -> OptionalLong.of(EXPECTED),
        "tenant-a",
        "shift-1");
  }

  private static TelemetryGateway gateway(MemoryMessageBus bus) {
    return new TelemetryGateway(bus, "tenant-a", "device-1");
  }
}
