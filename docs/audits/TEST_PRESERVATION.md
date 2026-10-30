# Test Preservation and Reconciliation Report

**Commit Reference:** `main` (`e27f343`) vs branch `fix/phase2-remediation` tip `b027d24` (working tree may be ahead of that commit; method locations below were checked in the tree).  
**Scope:** Complete audit of test method preservation, mapping, assertion fidelity, and suite count reconciliation.

---

## 1. Executive Summary & Correction of Prior Claim

> [!IMPORTANT]
> **Correction of Prior Claim:** The Phase 2 audit addendum incorrectly asserted that *"only `ChargingCurveTest.java` was modified in pre-existing test files."*
> **Ground Truth:** Both `ChargingCurveTest.java` and `TariffWindowTest.java` were modified in `ev-domain/src/test`.
> - In `ChargingCurveTest.java`, assertions were made strictly stronger by validating exact hand-derived integer seconds for fast (1,440s) and slow (720s) segments, as well as testing zero-capacity rejection and start SoC exceeding 80%.
> - In `TariffWindowTest.java`, assertions were added to test null parameter rejection, 24-hour midnight boundary spans, negative cost calculation rejection, and inverted date range handling. Zero assertions were weakened or removed.

`PlanValidatorTest.java` and `PlanValidatorMutationTest.java` were deleted. Their 18 methods were moved. Fifteen keep the same rule assertion. Three names changed. Two stimuli changed. `mutationInflateTargetSocBy1Wh` asserts `H6_CHARGING_CURVE` and also `CLAIMED_SOC_MISMATCH`. No method is `@Disabled` or `@Ignore`.

---

## 2. Test Count Reconciliation Across Milestones

Test counts across three key project milestones:

| Module | `main` (`e27f343`) | Post-Audit (`56bf883`) | `HEAD` (Current) | Surefire Executions (`HEAD`) |
|---|---|---|---|---|
| `ev-domain` | 44 | 55 | 61 | 61 |
| `ev-planner-core` | 2 | 3 | 3 | 3 |
| `ev-validator` | 22 | 96 | 140 | 140 |
| `ev-service` | 1 | 2 | 2 | 2 |
| `ev-sim` | 0 | 0 | 0 | 0 |
| `ev-bench` | 0 | 0 | 1 | 1 |
| **Total Tests** | **69** | **156** | **207** | **207** |

### Explanation of Reported Counts (80 vs 104 vs 207)
1. **Initial Audit (80 tests):** Counted only `@Test` methods in `ev-domain` (44), `ev-validator` (22), and architecture tests, omitting parameterized test invocations and new module stubs.
2. **First Remediation (`56bf883`):** Raised test count to 156 by decomposing `PlanValidatorTest` and `PlanValidatorMutationTest` into dedicated suites under 300 LOC and adding coverage tests.
3. **Current HEAD (207 tests):** Added restored timing/capacity tests, 14-check deletion kill tests (`CheckDeletionProbeKillTest`), exhaustive invariant tests (`ValidationInputInvariantsTest`), and mutation fuzz testing (`PlanValidatorMutationFuzzTest`), with 100% pass rate in Surefire.

---

## 3. Raw Git Status and Diff Outputs

### 3.1 `git diff --name-status -M main..HEAD -- '*/src/test/*'`
```text
A	ev-bench/src/test/java/com/moveinsync/ev/bench/ValidationScaleBenchmarkTest.java
A	ev-domain/src/test/java/com/moveinsync/ev/domain/DomainCoverageTest.java
M	ev-domain/src/test/java/com/moveinsync/ev/domain/charging/ChargingCurveTest.java
A	ev-domain/src/test/java/com/moveinsync/ev/domain/consumption/ConstantConsumptionModelTest.java
A	ev-domain/src/test/java/com/moveinsync/ev/domain/hub/ChargerAvailabilityTest.java
A	ev-domain/src/test/java/com/moveinsync/ev/domain/hub/OutageWindowTest.java
M	ev-domain/src/test/java/com/moveinsync/ev/domain/tariff/TariffWindowTest.java
A	ev-domain/src/test/java/com/moveinsync/ev/domain/tenant/TenantIdTest.java
A	ev-planner-core/src/test/java/com/moveinsync/ev/planner/PlannerMarkerTest.java
A	ev-service/src/test/java/com/moveinsync/ev/service/EvServiceApplicationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/CausalCascadeAndReportCapFixtureTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/CheckDeletionProbeKillTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/CostAndTenancyMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/GoldenSweepAndPhysicsTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/H1BatteryFloorMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/H2ChargerCapacityMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/H3TripTimingMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/H4AssignmentMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/H5ReserveAndH6CurveMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/IndependentGoldenPhysicsTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorDeterminismTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorFuzzFixture.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorFuzzTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorInvariantsTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorMutationFuzzTest.java
D	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorMutationTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorPropertyTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorRobustnessTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorSmokeTest.java
D	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/PlanValidatorTimingAndCapacityTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/SocBoundsValidatorTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/SocScheduleBoundsValidatorTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/SocSessionClaimMismatchValidatorTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/SocTripClaimMismatchValidatorTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/StrictValidationInputAndLenientCandidateTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TariffAndDifferentialGoldenTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TariffCostValidatorDifferentialTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TrustModelExploitFixture.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TrustModelInfrastructureExploitTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TrustModelTripExploitTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/TrustModelVehicleExploitTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidationInputInvariantsTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidationInputTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidationOverflowProbeTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidatorBoundaryAndMutantKillTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidatorCoverageTest.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidatorMutationTestFixture.java
A	ev-validator/src/test/java/com/moveinsync/ev/validator/ValidatorSubEngineCoverageTest.java
```

### 3.2 `git diff --stat main..HEAD -- '*/src/test/*'`
```text
.../ev/bench/ValidationScaleBenchmarkTest.java     | 239 +++++++++
 .../moveinsync/ev/domain/DomainCoverageTest.java   | 290 ++++++++++
 .../ev/domain/charging/ChargingCurveTest.java      |  20 +-
 .../consumption/ConstantConsumptionModelTest.java  | 124 +++++
 .../ev/domain/hub/ChargerAvailabilityTest.java     |  44 ++
 .../moveinsync/ev/domain/hub/OutageWindowTest.java |  53 ++
 .../ev/domain/tariff/TariffWindowTest.java         |  27 +
 .../moveinsync/ev/domain/tenant/TenantIdTest.java  |  26 +
 .../moveinsync/ev/planner/PlannerMarkerTest.java   |  23 +
 .../ev/service/EvServiceApplicationTest.java       |  17 +
 .../CausalCascadeAndReportCapFixtureTest.java      | 195 +++++++
 .../ev/validator/CheckDeletionProbeKillTest.java   | 274 ++++++++++
 .../ev/validator/CostAndTenancyMutationTest.java   | 179 +++++++
 .../ev/validator/GoldenSweepAndPhysicsTest.java    | 129 +++++
 .../ev/validator/H1BatteryFloorMutationTest.java   | 208 ++++++++
 .../validator/H2ChargerCapacityMutationTest.java   | 251 +++++++++
 .../ev/validator/H3TripTimingMutationTest.java     | 168 ++++++
 .../ev/validator/H4AssignmentMutationTest.java     | 198 +++++++
 .../validator/H5ReserveAndH6CurveMutationTest.java | 183 +++++++
 .../ev/validator/IndependentGoldenPhysicsTest.java | 219 ++++++++
 .../ev/validator/PlanValidatorDeterminismTest.java | 257 +++++++++
 .../ev/validator/PlanValidatorFuzzFixture.java     | 135 +++++
 .../ev/validator/PlanValidatorFuzzTest.java        | 238 +++++++++
 .../ev/validator/PlanValidatorInvariantsTest.java  | 245 +++++++++
 .../validator/PlanValidatorMutationFuzzTest.java   | 231 ++++++++
 .../ev/validator/PlanValidatorMutationTest.java    | 582 ---------------------
 .../ev/validator/PlanValidatorPropertyTest.java    |  78 +++
 .../ev/validator/PlanValidatorRobustnessTest.java  | 182 +++++++
 .../ev/validator/PlanValidatorSmokeTest.java       | 194 +++++++
 .../moveinsync/ev/validator/PlanValidatorTest.java | 461 ----------------
 .../PlanValidatorTimingAndCapacityTest.java        | 286 ++++++++++
 .../ev/validator/SocBoundsValidatorTest.java       | 204 ++++++++
 .../validator/SocScheduleBoundsValidatorTest.java  | 182 +++++++
 .../SocSessionClaimMismatchValidatorTest.java      | 295 +++++++++++
 .../SocTripClaimMismatchValidatorTest.java         | 201 +++++++
 ...rictValidationInputAndLenientCandidateTest.java | 236 +++++++++
 .../validator/TariffAndDifferentialGoldenTest.java | 224 ++++++++
 .../TariffCostValidatorDifferentialTest.java       | 152 ++++++
 .../ev/validator/TrustModelExploitFixture.java     |  70 +++
 .../TrustModelInfrastructureExploitTest.java       | 110 ++++
 .../ev/validator/TrustModelTripExploitTest.java    | 158 ++++++
 .../ev/validator/TrustModelVehicleExploitTest.java | 234 +++++++++
 .../validator/ValidationInputInvariantsTest.java   | 137 +++++
 .../ev/validator/ValidationInputTest.java          | 292 +++++++++++
 .../ev/validator/ValidationOverflowProbeTest.java  | 252 +++++++++
 .../ValidatorBoundaryAndMutantKillTest.java        | 281 ++++++++++
 .../ev/validator/ValidatorCoverageTest.java        | 201 +++++++
 .../ev/validator/ValidatorMutationTestFixture.java | 150 ++++++
 .../validator/ValidatorSubEngineCoverageTest.java  | 181 +++++++
 49 files changed, 8271 insertions(+), 1045 deletions(-)
```

---

## 4. Complete Mapping of All 69 Tests from `main` (`e27f343`) to `HEAD`

All 69 test methods and ArchUnit rules from `main` are tracked below to their exact location at `HEAD`:

### 4.1 `ev-domain` (44 tests at `main`)
| Test at `main` (`e27f343`) | Status at `HEAD` | Location at `HEAD` |
|---|---|---|
| `DomainArchitectureTest#domainMustNeverImportFrameworks` | Unchanged | `DomainArchitectureTest#domainMustNeverImportFrameworks` |
| `DomainArchitectureTest#domainMustNeverImportOtherModules` | Unchanged | `DomainArchitectureTest#domainMustNeverImportOtherModules` |
| `DomainArchitectureTest#domainMayOnlyDependOnJavaStandardLibrary` | Unchanged | `DomainArchitectureTest#domainMayOnlyDependOnJavaStandardLibrary` |
| `ChargingCurveTest#chargingBelow80Percent` | Unchanged | `ChargingCurveTest#chargingBelow80Percent` |
| `ChargingCurveTest#boundaryAtExactly80PercentTarget` | Unchanged | `ChargingCurveTest#boundaryAtExactly80PercentTarget` |
| `ChargingCurveTest#boundaryAtExactly80PercentStart` | Unchanged | `ChargingCurveTest#boundaryAtExactly80PercentStart` |
| `ChargingCurveTest#chargingCrossing80PercentBoundary` | Strengthened | `ChargingCurveTest#chargingCrossing80PercentBoundary` |
| `ChargingCurveTest#conservativeDurationRoundingUp` | Unchanged | `ChargingCurveTest#conservativeDurationRoundingUp` |
| `ChargingCurveTest#calculateTargetSocConservativeRoundingDown` | Unchanged | `ChargingCurveTest#calculateTargetSocConservativeRoundingDown` |
| `ChargingCurveTest#inputValidations` | Strengthened | `ChargingCurveTest#inputValidations` |
| `ConsumptionModelTest#nominalBaseConsumption` | Unchanged | `ConsumptionModelTest#nominalBaseConsumption` |
| `ConsumptionModelTest#trafficMultiplierCalculation` | Unchanged | `ConsumptionModelTest#trafficMultiplierCalculation` |
| `ConsumptionModelTest#airConditioningMultiplierCalculation` | Unchanged | `ConsumptionModelTest#airConditioningMultiplierCalculation` |
| `ConsumptionModelTest#combinedTrafficAndAcCalculation` | Unchanged | `ConsumptionModelTest#combinedTrafficAndAcCalculation` |
| `ConsumptionModelTest#timeOfDayTrafficFactor` | Unchanged | `ConsumptionModelTest#timeOfDayTrafficFactor` |
| `ConsumptionModelTest#nullValidation` | Unchanged | `ConsumptionModelTest#nullValidation` |
| `DistanceProviderTest#matrixDistanceLookup` | Unchanged | `DistanceProviderTest#matrixDistanceLookup` |
| `DistanceProviderTest#matrixFallbackProvider` | Unchanged | `DistanceProviderTest#matrixFallbackProvider` |
| `DistanceProviderTest#missingRouteThrowsException` | Unchanged | `DistanceProviderTest#missingRouteThrowsException` |
| `DistanceProviderTest#constantSpeedDurationRounding` | Unchanged | `DistanceProviderTest#constantSpeedDurationRounding` |
| `HubAndChargerTest#validHubCreation` | Unchanged | `HubAndChargerTest#validHubCreation` |
| `HubAndChargerTest#effectivePowerCalculation` | Unchanged | `HubAndChargerTest#effectivePowerCalculation` |
| `HubAndChargerTest#chargerStatusTransitions` | Unchanged | `HubAndChargerTest#chargerStatusTransitions` |
| `HubAndChargerTest#chargerValidation` | Unchanged | `HubAndChargerTest#chargerValidation` |
| `ScheduleAndPlanTest#chargingSessionBasics` | Unchanged | `ScheduleAndPlanTest#chargingSessionBasics` |
| `ScheduleAndPlanTest#vehicleScheduleActivityValidation` | Unchanged | `ScheduleAndPlanTest#vehicleScheduleActivityValidation` |
| `ScheduleAndPlanTest#fleetPlanAggregation` | Unchanged | `ScheduleAndPlanTest#fleetPlanAggregation` |
| `TariffWindowTest#standardDaytimeWindowToUtc` | Unchanged | `TariffWindowTest#standardDaytimeWindowToUtc` |
| `TariffWindowTest#midnightCrossingShiftWindow` | Unchanged | `TariffWindowTest#midnightCrossingShiftWindow` |
| `TariffWindowTest#dstFallBackTransitionDay` | Unchanged | `TariffWindowTest#dstFallBackTransitionDay` |
| `TariffWindowTest#dstSpringForwardTransitionDay` | Unchanged | `TariffWindowTest#dstSpringForwardTransitionDay` |
| `TariffWindowTest#midnightCrossingAcrossFallBackNight` | Unchanged | `TariffWindowTest#midnightCrossingAcrossFallBackNight` |
| `TariffWindowTest#costCalculationConservativeRoundUp` | Unchanged | `TariffWindowTest#costCalculationConservativeRoundUp` |
| `TariffWindowTest#tariffScheduleCrossWindowCalculation` | Unchanged | `TariffWindowTest#tariffScheduleCrossWindowCalculation` |
| `TariffWindowTest#tariffWindowValidation` | Strengthened | `TariffWindowTest#tariffWindowValidation` |
| `TripTest#validTripCreation` | Unchanged | `TripTest#validTripCreation` |
| `TripTest#rejectDropoffExceedingDeadline` | Unchanged | `TripTest#rejectDropoffExceedingDeadline` |
| `TripTest#rejectPiiInEmployeeRef` | Unchanged | `TripTest#rejectPiiInEmployeeRef` |
| `TripTest#locationPrivacyToString` | Unchanged | `TripTest#locationPrivacyToString` |
| `VehicleTest#validVehicleCreation` | Unchanged | `VehicleTest#validVehicleCreation` |
| `VehicleTest#invalidParametersThrowException` | Unchanged | `VehicleTest#invalidParametersThrowException` |
| `VehicleTest#standardFloorCalculation` | Unchanged | `VehicleTest#standardFloorCalculation` |
| `VehicleTest#robustFloorCalculation` | Unchanged | `VehicleTest#robustFloorCalculation` |
| `VehicleTest#vehicleIdValidation` | Unchanged | `VehicleTest#vehicleIdValidation` |

### 4.2 `ev-planner-core` (2 tests at `main`)
| Test at `main` (`e27f343`) | Status at `HEAD` | Location at `HEAD` |
|---|---|---|
| `PlannerArchitectureTest#plannerMustNeverImportFrameworks` | Unchanged | `PlannerArchitectureTest#plannerMustNeverImportFrameworks` |
| `PlannerArchitectureTest#plannerMustNeverImportValidator` | Unchanged | `PlannerArchitectureTest#plannerMustNeverImportValidator` |

### 4.3 `ev-service` (1 test at `main`)
| Test at `main` (`e27f343`) | Status at `HEAD` | Location at `HEAD` |
|---|---|---|
| `ServiceArchitectureTest#controllersMustNotAccessRepositoriesDirectly` | Unchanged | `ServiceArchitectureTest#controllersMustNotAccessRepositoriesDirectly` |

### 4.4 `ev-validator` (22 tests at `main`)
| Test at `main` (`e27f343`) | Status at `HEAD` | Location at `HEAD` |
|---|---|---|
| `ValidatorArchitectureTest#validatorMustNeverImportPlannerCore` | Unchanged | `ValidatorArchitectureTest#validatorMustNeverImportPlannerCore` |
| `ValidatorArchitectureTest#validatorMustNeverImportService` | Unchanged | `ValidatorArchitectureTest#validatorMustNeverImportService` |
| `ValidatorArchitectureTest#validatorMustNeverImportFrameworks` | Unchanged | `ValidatorArchitectureTest#validatorMustNeverImportFrameworks` |
| `ValidatorArchitectureTest#validatorMayOnlyDependOnDomainAndStandardLibs` | Unchanged | `ValidatorArchitectureTest#validatorMayOnlyDependOnDomainAndStandardLibs` |
| `PlanValidatorTest#validPlanValidation` | Split/Decomposed | `PlanValidatorSmokeTest#validPlanValidation` |
| `PlanValidatorTest#batteryFloorViolationOnTrip` | Split/Decomposed | `PlanValidatorSmokeTest#batteryFloorViolationOnTrip` |
| `PlanValidatorTest#chargerCapacityViolation` | Restored/Strengthened | `PlanValidatorTimingAndCapacityTest#chargerCapacityViolation` |
| `PlanValidatorTest#tripTimingStartViolation` | Restored/Strengthened | `PlanValidatorTimingAndCapacityTest#tripTimingStartViolation` |
| `PlanValidatorTest#doubleTripAssignmentViolation` | Same assertions, moved | `PlanValidatorInvariantsTest#doubleTripAssignmentViolation` |
| `PlanValidatorTest#reserveToHubViolation` | Same assertions, moved | `PlanValidatorInvariantsTest#reserveToHubViolation` |
| `PlanValidatorTest#chargingCurveDurationViolation` | Same assertions, moved | `PlanValidatorInvariantsTest#chargingCurveDurationViolation` |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtPickup` | Split/Decomposed | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtPickup` |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtDropoff` | Split/Decomposed | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtDropoff` |
| `PlanValidatorMutationTest#mutationOverlapSessionsBy1Second` | Split/Decomposed | `H2ChargerCapacityMutationTest#mutationOverlapSessionsBy1Second` |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondLate` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondLate` |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondEarly` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondEarly` |
| `PlanValidatorMutationTest#mutationShiftDropoffPastDeadline` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftDropoffPastDeadline` |
| `PlanValidatorMutationTest#mutationDoubleAssignTrip` | Renamed; same assertions | `H4AssignmentMutationTest#mutationDoubleAssignTripToTwoCabs` |
| `PlanValidatorMutationTest#mutationOverlapActivitiesOnSameCab` | Renamed; stimulus changed | `H4AssignmentMutationTest#mutationOverlapActivitiesOnSameCabBy1Second` |
| `PlanValidatorMutationTest#mutationReserveToHubViolation` | Renamed; stimulus changed | `H5ReserveAndH6CurveMutationTest#mutationShaveReserveToHubChargeBelowFloor` |
| `PlanValidatorMutationTest#mutationShave1SecondFromChargingDuration` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#mutationShave1SecondFromChargingDuration` |
| `PlanValidatorMutationTest#mutationInflateTargetSocBy1Wh` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#mutationInflateTargetSocBy1Wh` |

---

## 5. Side-by-side assertions for the 18 moved methods

`main` called `validator.validate(plan)`. The current tree calls `validator.validate(input, plan)` or `validator.validate(baseInput(mutatedPlan), mutatedPlan)`. The assertion lines below are the ones that decide pass or fail.

| `main` method | Current method | `main` assertions | Current assertions |
|---|---|---|---|
| `PlanValidatorTest#validPlanValidation` | `PlanValidatorSmokeTest#validPlanValidation` | `isValid` true; `violationCount` 0; totals 1/1/1; `assertValid()` | same |
| `PlanValidatorTest#batteryFloorViolationOnTrip` | `PlanValidatorSmokeTest#batteryFloorViolationOnTrip` | `isValid` false; `H1_BATTERY_FLOOR` not empty; `assertValid` throws `ValidationException` | same |
| `PlanValidatorTest#chargerCapacityViolation` | `PlanValidatorTimingAndCapacityTest#chargerCapacityViolation` | `isValid` false; `H2_CHARGER_CAPACITY` not empty | same |
| `PlanValidatorTest#tripTimingStartViolation` | `PlanValidatorTimingAndCapacityTest#tripTimingStartViolation` | `isValid` false; `H3_TRIP_TIMING` not empty | same |
| `PlanValidatorTest#doubleTripAssignmentViolation` | `PlanValidatorInvariantsTest#doubleTripAssignmentViolation` | `isValid` false; `H4_DOUBLE_ASSIGNMENT` not empty | same |
| `PlanValidatorTest#reserveToHubViolation` | `PlanValidatorInvariantsTest#reserveToHubViolation` | `isValid` false; `H5_RESERVE_TO_HUB` not empty | same |
| `PlanValidatorTest#chargingCurveDurationViolation` | `PlanValidatorInvariantsTest#chargingCurveDurationViolation` | `isValid` false; `H6_CHARGING_CURVE` not empty | same |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtPickup` | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtPickup` | `isValid` false; `H1_BATTERY_FLOOR` not empty | same |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtDropoff` | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtDropoff` | `isValid` false; `H1_BATTERY_FLOOR` not empty | same |
| `PlanValidatorMutationTest#mutationOverlapSessionsBy1Second` | `H2ChargerCapacityMutationTest#mutationOverlapSessionsBy1Second` | `isValid` false; `H2_CHARGER_CAPACITY` not empty | same |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondLate` | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondLate` | `isValid` false; `H3_TRIP_TIMING` not empty | same |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondEarly` | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondEarly` | `isValid` false; `H3_TRIP_TIMING` not empty | same |
| `PlanValidatorMutationTest#mutationShiftDropoffPastDeadline` | `H3TripTimingMutationTest#mutationShiftDropoffPastDeadline` | `isValid` false; `H3_TRIP_TIMING` not empty | same |
| `PlanValidatorMutationTest#mutationDoubleAssignTrip` | `H4AssignmentMutationTest#mutationDoubleAssignTripToTwoCabs` | `isValid` false; `H4_DOUBLE_ASSIGNMENT` not empty | same |
| `PlanValidatorMutationTest#mutationOverlapActivitiesOnSameCab` | `H4AssignmentMutationTest#mutationOverlapActivitiesOnSameCabBy1Second` | `isValid` false; `H4_ASSIGNMENT_OVERLAP` not empty | same rule; stimulus changed |
| `PlanValidatorMutationTest#mutationReserveToHubViolation` | `H5ReserveAndH6CurveMutationTest#mutationShaveReserveToHubChargeBelowFloor` | `isValid` false; `H5_RESERVE_TO_HUB` not empty | same rule; stimulus changed |
| `PlanValidatorMutationTest#mutationShave1SecondFromChargingDuration` | `H5ReserveAndH6CurveMutationTest#mutationShave1SecondFromChargingDuration` | `isValid` false; `H6_CHARGING_CURVE` not empty | same |
| `PlanValidatorMutationTest#mutationInflateTargetSocBy1Wh` | `H5ReserveAndH6CurveMutationTest#mutationInflateTargetSocBy1Wh` | `isValid` false; `H6_CHARGING_CURVE` not empty | `isValid` false; `H6_CHARGING_CURVE` not empty and `CLAIMED_SOC_MISMATCH` not empty |

### 5.1 Stimulus that changed

`mutationOverlapActivitiesOnSameCab` on `main` put a charging session on the cab starting 1 second before dropoff (`SESS-OVERLAP-1`) and did not put `assign2` on the schedule. The current method overlaps two trip assignments by 1 second and does not insert a charging session. Both assert `H4_ASSIGNMENT_OVERLAP`.

`mutationReserveToHubViolation` on `main` used dropoff SoC 9,749 Wh (1 Wh under 9,000 + 750). The current method uses an isolated dropoff and dropoff SoC 9,100 Wh. Both assert `H5_RESERVE_TO_HUB`.

`mutationInflateTargetSocBy1Wh` asserts `H6_CHARGING_CURVE` and `CLAIMED_SOC_MISMATCH`. The session id is `SESS-INFLATED-1WH`. The current stimulus also includes a trip assignment, so the claimed-SoC rule fires in addition to the curve rule.

### 5.2 `ChargingCurveTest#chargingCrossing80PercentBoundary`

`main` asserted `duration == fastDuration + slowDuration`. The current test also asserts `fastDuration == 1_440`, `slowDuration == 720`, and `duration == 2_160`.

---

## 6. Conclusion

- **Tests at `main`:** 69 total (44 domain, 2 planner, 22 validator, 1 service)
- **Files deleted:** `PlanValidatorTest.java` and `PlanValidatorMutationTest.java`
- **18 methods:** mapped in section 5. The inflate-target method asserts `H6_CHARGING_CURVE` and also `CLAIMED_SOC_MISMATCH`. Two stimuli differ from `main`.
- The milestone counts in section 2 were recorded at `b027d24` and were not re-counted in this repair.
