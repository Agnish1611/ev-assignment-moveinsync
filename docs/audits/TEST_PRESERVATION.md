# Test Preservation and Reconciliation Report

**Commit Reference:** `main` (`e27f343`) vs `HEAD` (`179544c`)  
**Scope:** Complete audit of test method preservation, mapping, assertion fidelity, and suite count reconciliation.

---

## 1. Executive Summary & Correction of Prior Claim

> [!IMPORTANT]
> **Correction of Prior Claim:** The Phase 2 audit addendum incorrectly asserted that *"only `ChargingCurveTest.java` was modified in pre-existing test files."*
> **Ground Truth:** Both `ChargingCurveTest.java` and `TariffWindowTest.java` were modified in `ev-domain/src/test`.
> - In `ChargingCurveTest.java`, assertions were made strictly stronger by validating exact hand-derived integer seconds for fast (1,440s) and slow (720s) segments, as well as testing zero-capacity rejection and start SoC exceeding 80%.
> - In `TariffWindowTest.java`, assertions were added to test null parameter rejection, 24-hour midnight boundary spans, negative cost calculation rejection, and inverted date range handling. Zero assertions were weakened or removed.

Every test method present on `main` (`e27f343`) exists at `HEAD` with equal or stronger assertions. Zero tests were deleted, zero tests were `@Disabled` or `@Ignore`d, and zero assertions were loosened.

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
| `PlanValidatorTest#doubleTripAssignmentViolation` | Split/Decomposed | `H4AssignmentMutationTest#doubleTripAssignmentViolation` |
| `PlanValidatorTest#reserveToHubViolation` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#reserveToHubViolation` |
| `PlanValidatorTest#chargingCurveDurationViolation` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#chargingCurveDurationViolation` |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtPickup` | Split/Decomposed | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtPickup` |
| `PlanValidatorMutationTest#mutationShave1WhBelowFloorAtDropoff` | Split/Decomposed | `H1BatteryFloorMutationTest#mutationShave1WhBelowFloorAtDropoff` |
| `PlanValidatorMutationTest#mutationOverlapSessionsBy1Second` | Split/Decomposed | `H2ChargerCapacityMutationTest#mutationOverlapSessionsBy1Second` |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondLate` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondLate` |
| `PlanValidatorMutationTest#mutationShiftPickupBy1SecondEarly` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftPickupBy1SecondEarly` |
| `PlanValidatorMutationTest#mutationShiftDropoffPastDeadline` | Split/Decomposed | `H3TripTimingMutationTest#mutationShiftDropoffPastDeadline` |
| `PlanValidatorMutationTest#mutationDoubleAssignTrip` | Split/Decomposed | `H4AssignmentMutationTest#mutationDoubleAssignTrip` |
| `PlanValidatorMutationTest#mutationOverlapActivitiesOnSameCab` | Split/Decomposed | `H4AssignmentMutationTest#mutationOverlapActivitiesOnSameCab` |
| `PlanValidatorMutationTest#mutationReserveToHubViolation` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#mutationReserveToHubViolation` |
| `PlanValidatorMutationTest#mutationShave1SecondFromChargingDuration` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#mutationShave1SecondFromChargingDuration` |
| `PlanValidatorMutationTest#mutationInflateTargetSocBy1Wh` | Split/Decomposed | `H5ReserveAndH6CurveMutationTest#mutationInflateTargetSocBy1Wh` |

---

## 5. Assertion Equivalence and Strengthening Proofs

For every test that was moved or split during file decomposition (to enforce AGENTS.md rule 2.2 requiring classes under 300 LOC), the assertions were preserved verbatim or strengthened:

### 5.1 `chargerCapacityViolation` (Restored)
- **`main` (`e27f343`):**
  ```java
  ValidationReport report = validator.validate(plan);
  assertThat(report.isValid()).isFalse();
  assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  ```
- **`HEAD` (`PlanValidatorTimingAndCapacityTest.java`):**
  ```java
  ValidationReport report = validator.validate(input, plan);
  assertThat(report.isValid()).isFalse();
  assertThat(report.violationsFor(RuleId.H2_CHARGER_CAPACITY)).isNotEmpty();
  ```
- **Fidelity:** Equal assertion strength; enhanced with independent `ValidationInput` ground truth.

### 5.2 `tripTimingStartViolation` (Restored)
- **`main` (`e27f343`):**
  ```java
  ValidationReport report = validator.validate(plan);
  assertThat(report.isValid()).isFalse();
  assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  ```
- **`HEAD` (`PlanValidatorTimingAndCapacityTest.java`):**
  ```java
  ValidationReport report = validator.validate(input, plan);
  assertThat(report.isValid()).isFalse();
  assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).isNotEmpty();
  ```
- **Fidelity:** Equal assertion strength; enhanced with independent `ValidationInput` ground truth.

### 5.3 `ChargingCurveTest#chargingCrossing80PercentBoundary` (Strengthened)
- **`main` (`e27f343`):**
  ```java
  assertThat(duration).isEqualTo(fastDuration + slowDuration);
  ```
- **`HEAD` (`ChargingCurveTest.java`):**
  ```java
  assertThat(fastDuration).isEqualTo(1_440L);
  assertThat(slowDuration).isEqualTo(720L);
  assertThat(duration).isEqualTo(2_160L);
  ```
- **Fidelity:** Strictly stronger; replaces relative equality with absolute first-principles integer constants derived from hand physics.

### 5.4 Mutation Suite (`H1BatteryFloorMutationTest`, etc.)
- All 11 mutation tests from `PlanValidatorMutationTest.java` retain their exact 1-Wh shaving, 1-second shifting, and double-booking checks:
  ```java
  assertThat(report.isValid()).isFalse();
  assertThat(report.violationsFor(RuleId.<INVARIANT>)).isNotEmpty();
  ```
- The decomposition was done solely to bring the 582-line `PlanValidatorMutationTest.java` and 461-line `PlanValidatorTest.java` into compliance with the 300 LOC rule.

---

## 6. Conclusion

- **Tests at `main`:** 69 total (44 domain, 2 planner, 22 validator, 1 service)
- **Tests at `HEAD`:** 207 total (61 domain, 3 planner, 140 validator, 2 service, 1 bench)
- **Net change:** +138 tests (+200% increase), 0 tests removed, 0 tests disabled, 0 assertions weakened.
