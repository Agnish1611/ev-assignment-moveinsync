package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import java.util.List;
import java.util.Map;

/** Seeded corruptions for operational validator rules. */
final class PlanValidatorOpsRuleProbes {
  static RuleProbe claimedSocProbe(PlanValidatorFuzzFixture fixture) {
    TripAssignment shaved =
        RuleProbeSupport.assignment(
            fixture, fixture.t0.plusSeconds(600), fixture.t0.plusSeconds(1200), 50_000L, 49_249L);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(shaved));
  }

  static RuleProbe unavailableProbe(PlanValidatorFuzzFixture fixture) {
    Charger faulty = fixture.charger.withStatus(ChargerStatus.FAULTY);
    return new RuleProbe(
        RuleProbeSupport.input(
            fixture, false, faulty, RuleProbeSupport.distances(fixture), fixture.startSoc),
        fixture.validPlan);
  }

  static RuleProbe doubleBookedProbe(PlanValidatorFuzzFixture fixture) {
    ChargingSession second =
        RuleProbeSupport.copySession(
            fixture, "S-PLUG", fixture.session.startSocWh(), fixture.session.targetSocWh());
    return RuleProbeSupport.scheduleProbe(fixture, List.of(fixture.session, second));
  }

  static RuleProbe omittedProbe(PlanValidatorFuzzFixture fixture) {
    return new RuleProbe(fixture.input, RuleProbeSupport.plan(fixture, List.of(), List.of()));
  }

  static RuleProbe unknownUnassignedProbe(PlanValidatorFuzzFixture fixture) {
    Trip ghost =
        new Trip(
            fixture.tenant,
            TripId.of("TRIP-UNK"),
            fixture.t0.plusSeconds(600),
            fixture.pickup,
            fixture.dropoff,
            5_000,
            600,
            fixture.t0.plusSeconds(3_600),
            "EMP");
    return new RuleProbe(
        fixture.input,
        RuleProbeSupport.plan(
            fixture,
            List.of(),
            List.of(
                UnassignedTrip.of(
                    ghost, UnassignedReasonCode.INTRINSICALLY_INFEASIBLE, "backup"))));
  }

  static RuleProbe bothListsProbe(PlanValidatorFuzzFixture fixture) {
    return new RuleProbe(
        fixture.input,
        RuleProbeSupport.plan(
            fixture,
            List.of(fixture.schedule),
            List.of(
                UnassignedTrip.of(
                    fixture.trip, UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW, "backup"))));
  }

  static RuleProbe costProbe(PlanValidatorFuzzFixture fixture) {
    ChargingSession session = fixture.session;
    ChargingSession priced =
        new ChargingSession(
            session.sessionId(),
            session.vehicleId(),
            session.hubId(),
            session.chargerId(),
            session.startTime(),
            session.endTime(),
            session.startSocWh(),
            session.targetSocWh(),
            session.costMicros() + 1);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(priced));
  }

  static RuleProbe socBoundsProbe(PlanValidatorFuzzFixture fixture) {
    return RuleProbeSupport.scheduleProbe(
        fixture, List.of(RuleProbeSupport.copySession(fixture, "S-BOUND", 49_250L, 60_001L)));
  }

  static RuleProbe routeProbe(PlanValidatorFuzzFixture fixture) {
    DistanceProvider missing = new MatrixDistanceProvider(Map.of());
    return new RuleProbe(
        RuleProbeSupport.input(fixture, false, fixture.charger, missing, fixture.startSoc),
        fixture.validPlan);
  }

  static RuleProbe horizonProbe(PlanValidatorFuzzFixture fixture) {
    TripAssignment early =
        RuleProbeSupport.assignment(
            fixture, fixture.t0.minusSeconds(30), fixture.t0.plusSeconds(570), 50_000L, 49_250L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            fixture.cab,
            fixture.pickup,
            fixture.startSoc,
            fixture.t0.minusSeconds(30),
            List.of(early));
    return new RuleProbe(
        fixture.input, RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }
}
