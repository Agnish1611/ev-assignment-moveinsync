# Phase 2 closure

Branch `fix/phase2-remediation`. This file is the Phase 2 report for the working tree. It does not start Phase 3. History was not rewritten. Nothing here is merged.

## What is closed

Ground-truth types require an explicit tenant (`D17`). `scripts/verify.sh` grep gates fail closed when grep exits 2 or higher, and `./scripts/verify.sh --self-test` runs in CI before the full gate. `a1c9be4` stays in history (option B in `docs/audits/HISTORY_OPTIONS.md`): that commit does not compile its tests, and rewriting it is forbidden. The public validator entry on this tree is `PlanValidator()` and `ValidationReport validate(ValidationInput, FleetPlan)`.

Charging physics stays `D15`: full plug power to 80 percent, half power above that, integer seconds, energy rounded conservatively. `H5ReserveAndH6CurveMutationTest#mutationInflateTargetSocBy1Wh` asserts both `H6_CHARGING_CURVE` and `CLAIMED_SOC_MISMATCH`.

Ingestion is the boundary already decided in `D16` and `D5`. Ground truth is built only through `ValidationInputBuilder`, which rejects a missing tenant before it fills defaults. The candidate `FleetPlan` stays lenient so a bad plan becomes a report. A null input or a null candidate becomes `VALIDATOR_INTERNAL_ERROR`.

`PlanValidatorRuleCatalogFuzzTest` runs 8 trials from seed `20261030` and, for every `RuleId`, asserts that a seeded corruption reports that rule. Extra rules on the same plan are allowed.

## Check deletion, re-run on this tree

On 2026-10-30 each condition below was disabled with `if (false && …)`, the named test was run, and the source was restored in the same step. All 14 tests failed. A search of `ev-validator/src/main` for `false &&` after the run found nothing. Probe 2's first attempt stopped in Spotless because the disabled line was too long; it was re-run with the wrapped form and failed in `SocScheduleBoundsValidatorTest` (1 test, 1 failure).

| # | Check | Test that failed |
|---|---|---|
| 1 | `BatteryFloorValidator` initial SoC below floor | `ValidatorSubEngineCoverageTest#initialSocBelowFloor` |
| 2 | `BatteryFloorValidator` initial SoC out of bounds | `SocScheduleBoundsValidatorTest#initialSocExceedingCapacityProducesSocOutOfRange` |
| 3 | `TripActivityEnergyValidator` dropoff SoC below floor | `PlanValidatorSmokeTest#batteryFloorViolationOnTrip` |
| 4 | `TripActivityEnergyValidator` arrival SoC below floor | `CausalCascadeAndReportCapFixtureTest#singleDelayedTripCascadesIntoDownstreamViolationsTest` |
| 5 | `TripActivityEnergyValidator` departure SoC mismatch | `CausalCascadeAndReportCapFixtureTest#singleDelayedTripCascadesIntoDownstreamViolationsTest` |
| 6 | `ChargingActivityEnergyValidator` session start SoC mismatch | `H1BatteryFloorMutationTest#mutationRaiseSessionStartSocAboveArrivalSocAtHub` |
| 7 | `ChargingActivityEnergyValidator` target SoC mismatch | `H5ReserveAndH6CurveMutationTest#mutationInflateTargetSocBy1Wh` |
| 8 | `ChargingActivityEnergyValidator` session start below floor | `CheckDeletionProbeKillTest#sessionStartBelowBatteryFloorExplicitlyReported` |
| 9 | `ChargingCurveValidator` duration shorter than the curve | `H5ReserveAndH6CurveMutationTest#mutationShave1SecondFromChargingDuration` |
| 10 | `ChargerCapacityValidator` hub occupancy above charger count | `PlanValidatorTimingAndCapacityTest#chargerCapacityViolation` |
| 11 | `ChargerCapacityValidator` overlapping sessions on one plug | `CheckDeletionProbeKillTest#plugConflictDetectedEvenWhenHubHasSpareCapacity` |
| 12 | `TripTimingValidator` pickup time not equal to `startTs` | `PlanValidatorTimingAndCapacityTest#tripTimingStartViolation` |
| 13 | `TripTimingValidator` dropoff before physical duration | `CheckDeletionProbeKillTest#tripDropoffBeforeEstimatedTripDurationRejected` |
| 14 | `AssignmentValidator` one trip on two vehicles | `PlanValidatorMutationFuzzTest#singleRandomFieldCorruptionAlwaysProducesExpectedViolation` |

## PIT

Command: `./mvnw -pl ev-validator -Ppitest org.pitest:pitest-maven:mutationCoverage`

Finished 2026-10-30T19:21:37+05:30. Profile `pitest`, mutator group `STRONGER`, target classes `com.moveinsync.ev.validator.*`. The catalog test is included. Property and architecture tests stay excluded as they already were. No class was dropped from the target list.

Result: 528 mutations, 420 killed (80%), 105 survived, 3 with no coverage. Test strength 80%. Line coverage of mutated classes 1291/1325 (97%). 33 test classes, 6089 tests executed. Build success.

90% of 528 is 476 killed. This run is 56 kills short. The mutator set was not weakened and no class was excluded to move the percentage. That choice is in `docs/DECISIONS.md`.

`ev-domain` PIT was not re-run in this session. The 81.1% figure in `docs/audits/VERIFICATION_PHASE2.md` is the earlier measurement.

The three uncovered mutants are `ChargerCapacityValidator` hub-id sort lambda line 42 (`EmptyObjectReturnValsMutator`) and the equal-timestamp dedupe at `runEventSweep` line 195 (both `RemoveConditional` arms). The survived rows are the rest of the table. Boundary mutants sit on inclusive floors and SoC clamps that another rule also rejects. Empty returns on sort keys and static message lambdas leave the violation set unchanged. Null-return and removed-conditional survivors are mostly guards that current fixtures always pass through. They are listed rather than suppressed.

| Status | Class | Method | Line | Mutator |
|---|---|---|---|---|
| SURVIVED | AssignmentValidator | checkTransitDuration | 138 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | AssignmentValidator | lambda$1 | 52 | EmptyObjectReturnValsMutator |
| SURVIVED | AssignmentValidator | lambda$2 | 73 | EmptyObjectReturnValsMutator |
| SURVIVED | AssignmentValidator | resolveEndLocation | 162 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | AssignmentValidator | resolveEndLocation | 162 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | AssignmentValidator | resolveEndLocation | 163 | NullReturnValsMutator |
| SURVIVED | AssignmentValidator | resolveStartLocation | 172 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | AssignmentValidator | resolveStartLocation | 173 | NullReturnValsMutator |
| SURVIVED | AssignmentValidator | resolveStartLocation | 175 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | AssignmentValidator | validateNoOverlap | 77 | VoidMethodCallMutator |
| SURVIVED | AssignmentValidator | validateNoOverlap | 84 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | AssignmentValidator | validateTransitGap | 109 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | AssignmentValidator | validateTransitGap | 109 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | AssignmentValidator | validateTransitGap | 109 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | AssignmentValidator | validateTransitGap | 123 | VoidMethodCallMutator |
| SURVIVED | BatteryFloorValidator | validateActivities | 87 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | BatteryFloorValidator | validateActivities | 92 | RemoveConditionalMutator_EQUAL_ELSE |
| NO_COVERAGE | ChargerCapacityValidator | lambda$0 | 42 | EmptyObjectReturnValsMutator |
| SURVIVED | ChargerCapacityValidator | lambda$3 | 141 | EmptyObjectReturnValsMutator |
| SURVIVED | ChargerCapacityValidator | runEventSweep | 181 | MathMutator |
| NO_COVERAGE | ChargerCapacityValidator | runEventSweep | 195 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargerCapacityValidator | runEventSweep | 195 | RemoveConditionalMutator_EQUAL_ELSE |
| NO_COVERAGE | ChargerCapacityValidator | runEventSweep | 195 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | ChargerCapacityValidator | validateChargerAvailability | 99 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 119 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 119 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 119 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 119 | RemoveConditionalMutator_ORDER_IF |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 134 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 134 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 134 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionSocBounds | 134 | RemoveConditionalMutator_ORDER_IF |
| SURVIVED | ChargingActivityEnergyValidator | checkSessionStartSoc | 101 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 30 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 43 | PrimitiveReturnsMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 54 | PrimitiveReturnsMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 62 | VoidMethodCallMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 63 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 63 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | validateChargingSession | 77 | PrimitiveReturnsMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateDeadheadToHub | 195 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | validateDeadheadToHub | 195 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | ChargingActivityEnergyValidator | validateDeadheadToHub | 199 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | validateDeadheadToHub | 216 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingActivityEnergyValidator | validateDeadheadToHub | 216 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ChargingActivityEnergyValidator | validateSessionDelivery | 183 | PrimitiveReturnsMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 42 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 62 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 62 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 62 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 78 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 78 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 78 | RemoveConditionalMutator_ORDER_IF |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 94 | ConditionalsBoundaryMutator |
| SURVIVED | ChargingCurveValidator | validateSessionDuration | 94 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | PlanReferentialIntegrityValidator | checkActivitiesIntegrity | 114 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanReferentialIntegrityValidator | checkActivitiesIntegrity | 114 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanReferentialIntegrityValidator | checkActivitiesIntegrity | 115 | VoidMethodCallMutator |
| SURVIVED | PlanReferentialIntegrityValidator | checkSessionIntegrity | 155 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanReferentialIntegrityValidator | checkSessionIntegrity | 163 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanReferentialIntegrityValidator | checkVehicleIntegrity | 41 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanTenancyValidator | checkTenants | 29 | VoidMethodCallMutator |
| SURVIVED | PlanTenancyValidator | checkUnassignedTenant | 177 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanTenancyValidator | checkUnassignedTenant | 177 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanTripCompletenessValidator | checkTripCompleteness | 36 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanTripCompletenessValidator | checkTripCompleteness | 45 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanTripCompletenessValidator | checkTripCompleteness | 61 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanValidator | failClosedReport | 95 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanValidator | failClosedReport | 95 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanValidator | failClosedReport | 97 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanValidator | failClosedReport | 97 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanValidator | failClosedReport | 97 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | PlanValidator | failClosedReport | 100 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanValidator | failClosedReport | 101 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | PlanValidator | lambda$static$0 | 24 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanValidator | lambda$static$1 | 25 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanValidator | lambda$static$2 | 26 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanValidator | lambda$static$3 | 27 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanViolation | deriveDefaultRootCauseKey | 62 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanViolation | lambda$deriveDefaultRootCauseKey$0 | 70 | EmptyObjectReturnValsMutator |
| SURVIVED | PlanViolation | lambda$deriveDefaultRootCauseKey$1 | 70 | EmptyObjectReturnValsMutator |
| SURVIVED | ReserveToHubValidator | validate | 28 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ReserveToHubValidator | validateTripReserve | 61 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ReserveToHubValidator | validateTripReserve | 71 | ConditionalsBoundaryMutator |
| SURVIVED | TariffCostValidator | calculateCostMicros | 131 | ConditionalsBoundaryMutator |
| SURVIVED | TariffCostValidator | calculateCostMicros | 139 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | TariffCostValidator | calculateCostMicros | 146 | ConditionalsBoundaryMutator |
| SURVIVED | TariffCostValidator | calculateCostMicros | 146 | RemoveConditionalMutator_ORDER_IF |
| SURVIVED | TariffCostValidator | resolveAllTariffs | 94 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | TariffCostValidator | resolveAllTariffs | 100 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | TariffCostValidator | resolveAllTariffs | 108 | VoidMethodCallMutator |
| SURVIVED | TariffCostValidator | validateSessionCost | 57 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | TariffCostValidator | validateSessionCost | 61 | MathMutator |
| SURVIVED | TariffCostValidator | validateSessionCost | 65 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | TripActivityEnergyValidator | calculateDeadheadEnergy | 197 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | TripActivityEnergyValidator | checkDeadheadClaimsAndFloor | 178 | ConditionalsBoundaryMutator |
| SURVIVED | TripActivityEnergyValidator | checkDeadheadRoute | 124 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | TripActivityEnergyValidator | checkDropoffSocFloorAndClaims | 66 | ConditionalsBoundaryMutator |
| SURVIVED | TripActivityEnergyValidator | checkDropoffSocFloorAndClaims | 67 | ConditionalsBoundaryMutator |
| SURVIVED | ValidationInput | <init> | 84 | ConditionalsBoundaryMutator |
| SURVIVED | ValidationInput | <init> | 84 | ConditionalsBoundaryMutator |
| SURVIVED | ValidationInput | <init> | 84 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ValidationInput | <init> | 88 | RemoveConditionalMutator_ORDER_ELSE |
| SURVIVED | ValidationInput | <init> | 99 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | ValidationInput | enforceReserveToHub | 233 | BooleanTrueReturnValsMutator |
| SURVIVED | ValidationInputBuilder | build | 164 | RemoveConditionalMutator_EQUAL_ELSE |
| SURVIVED | ValidationReport | toDetailedSummary | 127 | RemoveConditionalMutator_EQUAL_IF |
| SURVIVED | ValidationReport | toDetailedSummary | 136 | RemoveConditionalMutator_EQUAL_IF |

## Part C

`docs/audits/VERIFICATION_PHASE2.md` already records Part C, requirements (a) through (g) and sub-phases 3a through 3e. That plan is the one to follow when Phase 3 starts. It was not executed.

## Explicitly not Phase 2

Planner core, the re-plan ladder, the service publish path, and the 300-cab release-candidate benchmark are later phases. `scripts/verify.sh` skips the release-candidate benchmark unless `RELEASE_CANDIDATE` is set. That skip is the script's normal path.

The remediation is in the working tree and is not committed. Commit and merge wait for a separate instruction.
