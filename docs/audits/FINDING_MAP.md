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
| **F-02** | BLOCKER | SOUNDNESS | `85b0182`, `4a838a6` | `H1BatteryFloorMutationTest#mutationRaiseDepartureSocAfterDropoffBy1Wh`<br>`TrustModelTripExploitTest#exploitForgedDepartureAfterDropoffSoc`<br>`SocTripClaimMismatchValidatorTest#departureAfterDropoffSocPlus1WhProducesClaimedSocMismatch` |
| **F-03** | BLOCKER | LEAK | `85b0182`, `56bf883` | `SocSessionClaimMismatchValidatorTest#plugSocInflationSessionStartAboveArrivalProducesClaimedSocMismatch`<br>*(Dedicated 10,000 Wh arrival / 50,000 Wh declared start test)*<br>`H1BatteryFloorMutationTest#mutationRaiseSessionStartSocAboveArrivalSocAtHub` |
| **F-04** | BLOCKER | ROBUSTNESS | `be5c563`, `4a838a6` | `SocBoundsValidatorTest#targetSocExceedingCapacityProducesSocOutOfRange`<br>`SocBoundsValidatorTest#negativeStartSocProducesSocOutOfRange`<br>`SocSessionClaimMismatchValidatorTest#sessionEndSocPlus1WhProducesClaimedSocMismatch`<br>`PlanValidatorPropertyTest#malformedTargetSocNeverThrows` |
| **F-05** | MAJOR | ROBUSTNESS | `be5c563` | `H3TripTimingMutationTest#mutationMissingRoutingDistanceProducesRouteUnavailable` |
| **F-06** | MAJOR | SPEC | `40f3536`, `442ccea` | `H2ChargerCapacityMutationTest#mutationSessionOnFaultyCharger`<br>`H2ChargerCapacityMutationTest#mutationSessionOnMaintenanceCharger`<br>`H2ChargerCapacityMutationTest#mutationSessionDuringChargerOutageWindow` |
| **F-07** | MAJOR | SPEC | `08a2c81`, `4a838a6` | `CostAndTenancyMutationTest#mutationOmittedRequiredTripProducesTripOmitted`<br>`H4AssignmentMutationTest#mutationUnknownUnassignedTripProducesTripUnknown`<br>`TrustModelInfrastructureExploitTest#exploitOmittedRequiredTrip` |
| **F-08** | MAJOR | CONCURRENCY | `c6cc6e0` | `PlanValidatorDeterminismTest#validationReportIsStrictlyDeterministicAcrossPermutations` |
| **F-09** | MAJOR | LEAK | `72bb738`, `3fb6e1a` | `TariffCostValidatorDifferentialTest#randomSessionsMatchDomainSchedule`<br>`TariffCostValidatorDifferentialTest#dstFallBackMatchesDomainSchedule`<br>`CostAndTenancyMutationTest#mutationSessionCostAlteredByOneMicroUnit`<br>`TariffAndDifferentialGoldenTest#tariffCostAcrossWindowAndDstGoldenTest` |
| **F-10** | MINOR | CODE QUALITY | `e4cabba`, `183dad3` | Checkstyle validation with 0 violations; methods split under 35 LOC across `AssignmentValidator`, `BatteryFloorValidator`, and `TripTimingValidator`; class length justifications added. |
| **F-11** | MINOR | TEST QUALITY | `e4cabba`, `3fb6e1a` | `ConstantConsumptionModelTest#calculatesEnergyWithConservativeCeilingRounding`<br>`TariffAndDifferentialGoldenTest#namedDecisionD8DeadheadTrafficAndAcTest` |
| **F-12** | SPEC | SPEC | `40f3536`, `d6779a0` | `CostAndTenancyMutationTest#mutationCrossTenantTripProducesTenantMismatch`<br>`TrustModelVehicleExploitTest#combinedExploitIsRejected` |

---

## 2. Extended Findings (F-13 through F-18)

| Finding ID | Severity | Category | Remediating Commits | Exact Automated Test Method Names |
|:---|:---:|:---:|:---|:---|
| **F-13** | BLOCKER | TRUST MODEL | `d6779a0`, `4a838a6` | `TrustModelVehicleExploitTest#combinedExploitIsRejected`<br>`TrustModelVehicleExploitTest#exploitForgedVehicleCapacity`<br>`TrustModelVehicleExploitTest#exploitForgedVehicleConsumption`<br>`TrustModelVehicleExploitTest#exploitForgedVehicleMaxChargeKw`<br>`TrustModelVehicleExploitTest#exploitForgedInitialSoc`<br>`TrustModelVehicleExploitTest#exploitForgedInitialLocation`<br>`TrustModelTripExploitTest#exploitForgedTripDistance`<br>`TrustModelTripExploitTest#exploitForgedTripDuration`<br>`TrustModelTripExploitTest#exploitForgedTripStartTs`<br>`TrustModelTripExploitTest#exploitForgedTripLatestFinish`<br>`TrustModelTripExploitTest#exploitForgedTripPickupLocation`<br>`TrustModelTripExploitTest#exploitForgedTripDropoffLocation`<br>`TrustModelTripExploitTest#exploitForgedDepartureAfterDropoffSoc`<br>`TrustModelInfrastructureExploitTest#exploitForgedSessionCost`<br>`TrustModelInfrastructureExploitTest#exploitOmittedRequiredTrip` |
| **F-14** | MAJOR | SPEC | `40f3536` | `HubTest#hubCreationAndChargerRelationship`<br>`ChargerTest#chargerCreationAndAvailability`<br>`VehicleTest#vehicleCreationAndConsumption`<br>`TripTest#tripCreationAndTiming` |
| **F-15** | MAJOR | ROBUSTNESS | `d6779a0`, `619b8f3` | `PlanValidatorFuzzTest#fuzzedPlansNeverThrowAndRejectMalformed`<br>*(10,000 fuzzed trials asserting totality & zero thrown exceptions)*<br>`PlanValidatorRobustnessTest#nullCandidatePlanFailsClosedWithoutThrowing` |
| **F-16** | MAJOR | SPEC | `442ccea` | `H2ChargerCapacityMutationTest#mutationOverlapSessionsBy1Second` |
| **F-17** | MAJOR | GATE | `c1e321e`, `39221d6` | Verified via throwaway branch `audit/injection-proofs`: Step 3a (`System.out`), 3a (`printStackTrace`), 3b (wildcard imports), 3c (`Thread.sleep`), 3d (`catch Throwable`), 3e (`float/double`), 3f (`@Disabled`), 3g (empty catch), 3h (`Instant.now`), 3i (`TODO`), 3j (hardcoded secrets), Step 4 (PII logging). |
| **F-18** | MINOR | DOCS | `d8ef58e` | Root `README.md` committed and verified. |

---

## 3. Dedicated Verification of Original F-03

As required by the verification specification, original finding **F-03** has a dedicated test explicitly reproducing the 10,000 Wh arrival / 50,000 Wh declared start exploit:
- **Test Class:** `com.moveinsync.ev.validator.SocSessionClaimMismatchValidatorTest`
- **Method Name:** `plugSocInflationSessionStartAboveArrivalProducesClaimedSocMismatch()`
- **Test Invariant:** The vehicle arrives at the charging hub with an authoritative recomputed State of Charge of `10,000 Wh`. The candidate schedule fraudulently declares `session.startSocWh() = 50,000 Wh`.
- **Assertion:** `report.isValid()` is `false`, `RuleId.CLAIMED_SOC_MISMATCH` is emitted with message: `"claimed start SoC (50000 Wh) does not match arrival SoC at hub (10000 Wh)"`.
