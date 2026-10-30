# Phase 2 Audit Finding Mapping Matrix

**Source Branch:** `fix/phase2-remediation`  
**Base Commit:** `e27f343` (`main`)  
**Audit Document:** `docs/audits/AUDIT_REPORT_PHASE2.md` & `docs/audits/AUDIT_ADDENDUM_PHASE2.md`

This document provides a strict, one-to-one mapping between all audit findings (`F-01` through `F-18`) and their exact remediation commit hashes and automated regression test method names.

---

## 1. Original Audit Findings (F-01 through F-12)

| Finding ID | Severity | Category | Remediating Commits | Exact Automated Test Method Names |
|:---|:---:|:---:|:---|:---|
| **F-01** | CRITICAL | GATE | `c1e321e`, `6bc2d27`, `39221d6` | Enforced at build level via `scripts/verify.sh`: Spotless (`spotless:check`), Checkstyle (`checkstyle:check`), SpotBugs (`spotbugs:check`), JaCoCo line/branch gates (`jacoco:check`), and 12 forbidden construct greps. |
| **F-02** | BLOCKER | SOUNDNESS | `85b0182`, `4a838a6` | `PlanValidatorMutationTest#raisingClaimedDepartureAfterDropoffSocIsCaught`<br>`TrustModelExploitTest#exploitForgedDepartureAfterDropoffSoc`<br>`ClampingAndSocBoundsTest#departureAfterDropoffSocExceedingArrivalProducesClaimedSocMismatch` |
| **F-03** | BLOCKER | LEAK | `85b0182`, `56bf883` | `ClampingAndSocBoundsTest#plugSocInflationSessionStartAboveArrivalProducesClaimedSocMismatch`<br>*(Dedicated 10,000 Wh arrival / 50,000 Wh declared start test)*<br>`PlanValidatorMutationTest#raisingSessionStartSocAboveArrivalIsCaught` |
| **F-04** | BLOCKER | ROBUSTNESS | `be5c563`, `4a838a6` | `ClampingAndSocBoundsTest#targetSocExceedingBatteryCapacityProducesSocOutOfRange`<br>`ClampingAndSocBoundsTest#targetSocExceedingCurveDeliveryProducesClaimedSocMismatch`<br>`ClampingAndSocBoundsTest#negativeStartSocProducesSocOutOfRange`<br>`PlanValidatorPropertyTest#malformedTargetSocNeverThrows` |
| **F-05** | MAJOR | ROBUSTNESS | `be5c563` | `PlanValidatorMutationTest#missingRoutingDistanceProducesRouteUnavailable` |
| **F-06** | MAJOR | SPEC | `40f3536`, `442ccea` | `PlanValidatorMutationTest#sessionOnFaultyChargerProducesViolation`<br>`PlanValidatorMutationTest#sessionOnMaintenanceChargerProducesViolation`<br>`PlanValidatorMutationTest#sessionDuringChargerOutageWindowProducesViolation` |
| **F-07** | MAJOR | SPEC | `08a2c81`, `4a838a6` | `PlanValidatorMutationTest#omittedRequiredTripProducesTripOmittedViolation`<br>`PlanValidatorMutationTest#unknownUnassignedTripProducesTripUnknownViolation`<br>`TrustModelExploitTest#exploitOmittedRequiredTrip` |
| **F-08** | MAJOR | CONCURRENCY | `c6cc6e0` | `PlanValidatorDeterminismTest#validationReportIsStrictlyDeterministicAcrossPermutations` |
| **F-09** | MAJOR | LEAK | `72bb738`, `3fb6e1a` | `TariffCostValidatorDifferentialTest#randomSessionsMatchDomainSchedule`<br>`TariffCostValidatorDifferentialTest#dstFallBackMatchesDomainSchedule`<br>`PlanValidatorMutationTest#manipulatedChargingSessionCostProducesViolation`<br>`IndependentGoldenPhysicsTest#tariffCostAcrossWindowAndDstGoldenTest` |
| **F-10** | MINOR | CODE QUALITY | `e4cabba`, `183dad3` | Checkstyle validation with 0 violations; methods split under 35 LOC across `AssignmentValidator`, `BatteryFloorValidator`, and `TripTimingValidator`; class length justifications added. |
| **F-11** | MINOR | TEST QUALITY | `e4cabba`, `3fb6e1a` | `ConstantConsumptionModelTest#calculatesEnergyWithConservativeCeilingRounding`<br>`IndependentGoldenPhysicsTest#deadheadPlusTripEnergyGoldenTest` |
| **F-12** | SPEC | SPEC | `40f3536`, `d6779a0` | `PlanValidatorMutationTest#crossTenantTripProducesTenantMismatchViolation`<br>`TrustModelExploitTest#exploitTenantIsolationBypass` |

---

## 2. Extended Findings (F-13 through F-18)

| Finding ID | Severity | Category | Remediating Commits | Exact Automated Test Method Names |
|:---|:---:|:---:|:---|:---|
| **F-13** | BLOCKER | TRUST MODEL | `d6779a0`, `4a838a6` | `TrustModelExploitTest#combinedExploit12AttemptMustFailValidation`<br>`TrustModelExploitTest#exploitForgedVehicleCapacity`<br>`TrustModelExploitTest#exploitForgedVehicleConsumption`<br>`TrustModelExploitTest#exploitForgedVehicleMaxChargeKw`<br>`TrustModelExploitTest#exploitForgedInitialSoc`<br>`TrustModelExploitTest#exploitForgedInitialLocation`<br>`TrustModelExploitTest#exploitForgedTripDistance`<br>`TrustModelExploitTest#exploitForgedTripDuration`<br>`TrustModelExploitTest#exploitForgedTripStartTs`<br>`TrustModelExploitTest#exploitForgedTripLatestFinish`<br>`TrustModelExploitTest#exploitForgedTripPickupLocation`<br>`TrustModelExploitTest#exploitForgedTripDropoffLocation`<br>`TrustModelExploitTest#exploitForgedDepartureAfterDropoffSoc`<br>`TrustModelExploitTest#exploitForgedSessionCost`<br>`TrustModelExploitTest#exploitOmittedRequiredTrip` |
| **F-14** | MAJOR | SPEC | `40f3536` | `HubTest#hubCreationAndChargerRelationship`<br>`ChargerTest#chargerCreationAndAvailability`<br>`VehicleTest#vehicleCreationAndConsumption`<br>`TripTest#tripCreationAndTiming` |
| **F-15** | MAJOR | ROBUSTNESS | `d6779a0`, `619b8f3` | `PlanValidatorFuzzTest#fuzzedPlansNeverThrowAndRejectMalformed`<br>*(10,000 fuzzed trials asserting totality & zero thrown exceptions)*<br>`PlanValidatorRobustnessTest#nullCandidatePlanFailsClosedWithoutThrowing` |
| **F-16** | MAJOR | SPEC | `442ccea` | `PlanValidatorMutationTest#overlappingSessionsOnSamePhysicalChargerProducesViolation` |
| **F-17** | MAJOR | GATE | `c1e321e`, `39221d6` | Verified via throwaway branch `audit/injection-proofs`: Step 3a (`System.out`), 3a (`printStackTrace`), 3b (wildcard imports), 3c (`Thread.sleep`), 3d (`catch Throwable`), 3e (`float/double`), 3f (`@Disabled`), 3g (empty catch), 3h (`Instant.now`), 3i (`TODO`), 3j (hardcoded secrets), Step 4 (PII logging). |
| **F-18** | MINOR | DOCS | `d8ef58e` | Root `README.md` committed and verified. |

---

## 3. Dedicated Verification of Original F-03

As required by the verification specification, original finding **F-03** has a dedicated test explicitly reproducing the 10,000 Wh arrival / 50,000 Wh declared start exploit:
- **Test Class:** `com.moveinsync.ev.validator.ClampingAndSocBoundsTest`
- **Method Name:** `plugSocInflationSessionStartAboveArrivalProducesClaimedSocMismatch()`
- **Test Invariant:** The vehicle arrives at the charging hub with an authoritative recomputed State of Charge of `10,000 Wh`. The candidate schedule fraudulently declares `session.startSocWh() = 50,000 Wh`.
- **Assertion:** `report.isValid()` is `false`, `RuleId.CLAIMED_SOC_MISMATCH` is emitted with message: `"claimed start SoC (50000 Wh) does not match arrival SoC at hub (10000 Wh)"`.
