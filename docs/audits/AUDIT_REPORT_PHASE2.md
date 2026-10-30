# Phase 2 Adversarial Audit Report: EV Fleet Charging & Trip Assignment

- **Date:** 2026-10-30
- **Audited Commit:** `e27f343` (feat: implement independent plan validator and mutation tests (Phase 2))
- **Auditor:** Adversarial Auditor (Independent Senior Java Engineer)
- **Verdict:** **PROCEED AFTER FIXES** (3 BLOCKER, 5 MAJOR, 2 MINOR, 2 SPEC_ISSUE)

---

## 1. Executive Summary

### 1.1 Headline Verdict
The mathematical foundation of `ev-domain` and the high-level architecture of `ev-validator` are sound: **floating-point arithmetic is strictly zero**, all units (Wh, metres, epoch seconds UTC, micro-units) are properly typed as integers, and core invariants (H1–H6) have corresponding validator checks. Furthermore, ArchUnit boundary rules strictly isolate the validator from the planner and external frameworks.

However, the codebase currently suffers from **three BLOCKER vulnerabilities**:
1. **Verification Gates are Inactive in CI/Verify:** SpotBugs, Checkstyle, and JaCoCo coverage enforcements are declared only in parent `<pluginManagement>` and are **not bound to `<build><plugins>`**. Consequently, `./scripts/verify.sh` falsely passes green despite an unhandled SpotBugs warning (`PlanValidator.context` unread field) and JaCoCo line coverage in `ev-validator` sitting at **82.0%** (violating the 90% threshold mandated by `AGENTS.md`).
2. **Phantom Energy Creation in Battery Floor Validation:** In `BatteryFloorValidator.java:126`, the validator blindly accepts `assign.departureAfterDropoffSocWh()` as the vehicle's remaining energy without checking that `departureAfterDropoffSocWh == arrivalSocWh - tripEnergyWh`. A rogue or bug-ridden planner can set this to full capacity, completely erasing trip energy drain.
3. **Plug SoC Inflation:** In `BatteryFloorValidator.java:132-140`, the validator verifies `session.startSocWh() >= floorSocWh` but does not verify `session.startSocWh() <= arrivalSocAtHub`. A cab arriving at 10,000 Wh can declare a session starting at 50,000 Wh, magically skipping charging time.

Additionally, `ChargingCurveValidator` crashes with an unhandled `IllegalArgumentException` when `targetSocWh > batteryCapacityWh`, which would bring down the publication fallback pipeline instead of returning a clean `ValidationReport`.

**Conclusion:** The codebase must not proceed to Phase 3 (Planner Core) until the proposed fix plan is approved and executed.

---

### 1.2 Findings Summary by Severity

| Severity | Count | Primary Impact |
|:---|:---:|:---|
| **BLOCKER** | 3 | False-green build gates, energy creation leaks in validator, plug SoC inflation |
| **MAJOR** | 5 | Crash on malformed input, missing charger status check, unsorted non-deterministic reports, omitted trip silence, unhandled distance matrix exceptions |
| **MINOR** | 2 | Methods exceeding 40 lines without comments, uncovered domain class |
| **SPEC_ISSUE** | 2 | Multi-tenancy entity model vs plan-level tenant, deadhead wait-time vs transit timing semantics |
| **TOTAL** | **12** | |

---

### 1.3 Top 5 Risks Heading into Phase 3 (The Planner)

1. **Planner exploiting validator blindspots (Energy Leaks):** If `BatteryFloorValidator` accepts unverified departure SoC or unverified plug start SoC, ALNS repair operators could generate physically impossible schedules (free energy) that pass validation and reach production.
2. **Crash-induced pipeline failure:** If an ALNS candidate plan generates a session where `targetSocWh > batteryCapacityWh` or an unrouted location pair is evaluated, the validator throws unchecked runtime exceptions (`IllegalArgumentException`), failing the plan publication pipeline rather than providing structured diagnostic feedback.
3. **Building on false test coverage:** Without JaCoCo and SpotBugs active in `scripts/verify.sh`, untested branches and static bugs in the planner will slip into commits unnoticed.
4. **Charger status blindness:** If a charger is flagged `FAULTY` or undergoing maintenance, `ChargerCapacityValidator` currently permits bookings, creating false feasible schedules.
5. **Non-deterministic validator reports:** `HashMap` iteration in `ChargerCapacityValidator` and `AssignmentValidator` produces non-deterministic violation ordering, breaking determinism requirements and flaky tests during concurrency/scale runs.

---

### 1.4 Phase 2 Definition-of-Done Checklist

| DoD Requirement | Status | Evidence / Notes |
|:---|:---:|:---|
| `ev-validator` depends ONLY on `ev-domain` | **GREEN** | Proved by `ValidatorArchitectureTest` and Maven `dependency:tree`. |
| Zero float/double in domain and validator | **GREEN** | Grep verified: zero floating-point primitive or boxed types in constraint code. |
| Invariant H1: Battery floor $\ge 15\%$ including deadhead | **RED** | Invariant check present, but permits energy creation via unverified `departureAfterDropoffSocWh` and plug start SoC. |
| Invariant H2: Charger capacity event sweep | **YELLOW** | Sweep algorithm is exact and correct, but ignores `ChargerStatus` (permits booking `FAULTY` chargers). |
| Invariant H3: Trip timing (on time, before latest finish) | **GREEN** | Verified by unit and adversarial tests. |
| Invariant H4: Single cab, no overlap, single assignment | **GREEN** | Half-open intervals $[s, e)$ verified; double assignment caught. |
| Invariant H5: Reserve-to-hub floor maintenance | **GREEN** | Correctly computes transit energy to nearest hub and enforces 15% floor. |
| Invariant H6: Two-segment charging curve duration | **YELLOW** | Exact integer seconds verified, but crashes on `targetSocWh > batteryCapacityWh`. |
| Mutation tests for H1–H6 | **GREEN** | `PlanValidatorMutationTest` contains 11 active mutation tests covering all six rules. |
| Verification gate `scripts/verify.sh` green | **RED** | Script passes green only because SpotBugs, Checkstyle, and JaCoCo are inactive. |
| Coverage gates: $\ge 90\%$ line, $\ge 80\%$ branch for validator | **RED** | Actual coverage: Line 82.0% (<90%), Branch 74.5% (<80%). |

---

## 2. Baseline Measurements

### 2.1 Git Status & History
- Audit commit: `e27f343`
- Working tree: clean
- Recent commits:
  - `e27f343` feat: implement independent plan validator and mutation tests (Phase 2)
  - `44d4ed4` feat: implement core domain models, charging curve, and tariffs (Phase 1)
  - `b0b61b4` feat: initialize multi-module project scaffold, build verification, and architecture rules (Phase 0)

### 2.2 Module Architecture & Dependencies
```
com.moveinsync.ev:ev-fleet-assignment (pom)
├── com.moveinsync.ev:ev-domain (jar) - Dependencies: None (Pure Java)
├── com.moveinsync.ev:ev-planner-core (jar) - Dependencies: ev-domain
├── com.moveinsync.ev:ev-validator (jar) - Dependencies: ev-domain ONLY
├── com.moveinsync.ev:ev-service (jar) - Dependencies: ev-domain, ev-planner-core, ev-validator, Spring Boot
├── com.moveinsync.ev:ev-sim (jar) - Dependencies: ev-domain
└── com.moveinsync.ev:ev-bench (jar) - Dependencies: ev-domain, ev-planner-core
```

### 2.3 Test Count & Execution Metrics

| Module | Test Classes | Total Tests | Pass | Fail | Error | Duration |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| `ev-domain` | 9 | 44 | 44 | 0 | 0 | 0.45s |
| `ev-validator` | 3 | 22 | 22 | 0 | 0 | 0.42s |
| `ev-planner-core` | 1 | 2 | 2 | 0 | 0 | 0.05s |
| `ev-service` | 1 | 1 | 1 | 0 | 0 | 0.04s |
| **Total** | **14** | **69** | **69** | **0** | **0** | **0.96s** |

### 2.4 Code Coverage Baseline (JaCoCo)

| Module | Target Line | Actual Line | Status | Target Branch | Actual Branch | Status |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| `ev-domain` | N/A | **96.2%** (304/316) | OK | N/A | **86.1%** (93/108) | OK |
| `ev-validator` | $\ge 90.0\%$ | **82.0%** (237/289) | **FAIL** | $\ge 80.0\%$ | **74.5%** (82/110) | **FAIL** |
| `ev-planner-core` | $\ge 90.0\%$ | 100.0% (scaffold) | N/A | $\ge 80.0\%$ | 100.0% (scaffold) | N/A |
| `ev-service` | $\ge 75.0\%$ | 100.0% (scaffold) | N/A | N/A | N/A | N/A |

### 2.5 Verification Script Duration
- Command: `./scripts/verify.sh`
- Execution Time: **6.45 seconds**
- Result: Exited 0 (Passed), but **skipped Checkstyle, SpotBugs, and JaCoCo** due to POM configuration omissions.

---

## 3. Findings Table

| ID | Severity | Category | Location | Summary | Spec Ref | Evidence | Recommended Fix | Effort |
|:---|:---|:---|:---|:---|:---|:---|:---|:---:|
| **F-01** | **BLOCKER** | GATE | `pom.xml:135`, `scripts/verify.sh:13` | SpotBugs, Checkstyle & JaCoCo omitted from active build plugins; coverage fails spec | `AGENTS.md` Sec 2.3, 3, 9 | `mvn spotbugs:check` fails with `URF_UNREAD_FIELD`; JaCoCo shows 82% line / 74.5% branch in validator | Add plugins to `<build><plugins>` in parent and child POMs; bind to `verify` phase | 1h |
| **F-02** | **BLOCKER** | LEAK | `BatteryFloorValidator.java:126` | Validator accepts unverified `departureAfterDropoffSocWh`, allowing phantom battery regeneration | `AGENTS.md` Sec 1 (H1) | Line 126 returns `assign.departureAfterDropoffSocWh()` directly without checking `departureAfterDropoffSocWh == arrivalSoc - tripEnergy` | Enforce `departureAfterDropoffSocWh == arrivalSoc - tripEnergy` in `BatteryFloorValidator` | 30m |
| **F-03** | **BLOCKER** | LEAK | `BatteryFloorValidator.java:132-140` | Validator does not enforce `session.startSocWh() <= currentCabSocWh`, allowing plug SoC inflation | `AGENTS.md` Sec 1 (H1, H6) | Session start SoC is checked against floor, but not against incoming vehicle arrival SoC | Enforce `session.startSocWh() <= currentCabSocWh` before applying session gain | 30m |
| **F-04** | **BLOCKER** | ROBUSTNESS | `ChargingCurveValidator.java:65` | Validator crashes with unchecked `IllegalArgumentException` on `targetSocWh > capacity` | `AGENTS.md` Sec 1 (H6), Sec 2.4 | Probe 13 threw unhandled `IllegalArgumentException: targetSocWh (60001) must be within [0, 60000]` | Pre-validate `startSoc` and `targetSoc` bounds; emit `PlanViolation` instead of throwing | 30m |
| **F-05** | **MAJOR** | ROBUSTNESS | `MatrixDistanceProvider.java:31` | Missing distance entry crashes validator with unchecked exception instead of plan violation | `AGENTS.md` Sec 1, Sec 2.4 | Probe 15 threw `IllegalArgumentException: No distance entry for route from LOC-HUB-1 to LOC-UNKNOWN` | Catch or check routing presence; emit structured violation for unroutable paths | 45m |
| **F-06** | **MAJOR** | SPEC | `ChargerCapacityValidator.java:27-56` | Validator ignores `ChargerStatus`, allowing sessions on `FAULTY` or `MAINTENANCE` chargers | `docs/ARCHITECTURE.md` Sec 3.1, 7.2 | Probe 8 showed session on `ChargerStatus.FAULTY` produced 0 violations | Reject sessions scheduled on non-AVAILABLE chargers | 30m |
| **F-07** | **MAJOR** | SPEC | `FleetPlan.java:12`, `PlanValidator.java:67` | Validator cannot detect omitted trips because required input trips snapshot is not validated | `AGENTS.md` Sec 8 (Traceability) | Probe 7b omitted a trip entirely from plan; validator reported 0 violations | Provide input trip list to validator or check `FleetPlan.unassignedTrips()` partition | 1h |
| **F-08** | **MAJOR** | CONCURRENCY | `ChargerCapacityValidator.java:36`, `AssignmentValidator.java:43` | Violation ordering non-deterministic due to unordered `HashMap` iterations | `AGENTS.md` Sec 2.2, 6 | Unordered map traversal in event sweep and assignment groupers | Use `LinkedHashMap` or sort violations deterministically | 30m |
| **F-09** | **MAJOR** | LEAK | `PlanValidator.java:67-104` | Charging cost in `ChargingSession` is not validated against hub `TariffSchedule` | `AGENTS.md` Sec 2.1, 4.2 | No tariff validation in `PlanValidator`; session cost can be arbitrary | Add tariff recomputation check in validator or verify cost consistency | 1h |
| **F-10** | **MINOR** | CODE_QUALITY | `AssignmentValidator.java:38`, `BatteryFloorValidator.java:27`, `TripTimingValidator.java:32` | Methods exceed 40-line limit without justification comments | `AGENTS.md` Sec 2.2 | 43 LOC, 43 LOC, and 56 LOC respectively | Refactor into smaller sub-methods or add justification comments | 30m |
| **F-11** | **MINOR** | TEST_QUALITY | `ConstantConsumptionModel.java` | Class has 0% direct unit test coverage in `ev-domain` | `AGENTS.md` Sec 3 | JaCoCo shows 0/8 lines covered in `ev-domain` | Add direct parameterized unit tests in `ev-domain` | 20m |
| **F-12** | **SPEC_ISSUE** | SPEC | `Vehicle.java`, `Trip.java`, `Hub.java` | Entities lack `tenantId`; only `FleetPlan` has `tenantId` | `AGENTS.md` Sec 4.1 | Probe 16 verified domain entities do not carry `tenantId` | Document decision in `docs/DECISIONS.md` on whether entities inherit tenant from plan envelope | 20m |

---

## 4. Deep Dive on Findings

### 4.1 Verification Gates & Static Analysis (F-01)
- **Description:** In `pom.xml`, `checkstyle-maven-plugin`, `spotbugs-maven-plugin`, and `jacoco-maven-plugin` are declared inside `<pluginManagement>`, but omitted from the active `<build><plugins>` section. Consequently, running `./mvnw verify` only executes compilation, spotless formatting, and surefire tests.
- **Spec Reference:** `AGENTS.md` Section 2.3 ("Spotless, Checkstyle, SpotBugs and Error Prone run in verify. Zero warnings tolerated.") and Section 3 ("Coverage gates (JaCoCo, build fails below): ev-validator and ev-planner-core >= 90% line and >= 80% branch").
- **Evidence:**
  1. Injected an empty catch block `catch (Exception e) {}` into `BatteryFloorValidator.java` and ran `./scripts/verify.sh`. The script reported `✓ Maven verify passed` with 0 errors.
  2. Injected `@Disabled` on a test in `PlanValidatorTest.java` and ran `./scripts/verify.sh`. The script reported `✓ Maven verify passed` with 0 errors.
  3. Ran `./mvnw spotbugs:check` explicitly. The build failed with:
     ```
     [ERROR] Medium: Unread field: com.moveinsync.ev.validator.PlanValidator.context [com.moveinsync.ev.validator.PlanValidator] At PlanValidator.java:[line 24] URF_UNREAD_FIELD
     ```
  4. Ran `./mvnw jacoco:report` explicitly. In `ev-validator`, line coverage was **82.0%** (target 90%) and branch coverage was **74.5%** (target 80%).
- **Impact on Phase 3:** Critical. New planner algorithms will be developed without automated static analysis or coverage gating, allowing regressions, dead fields, and untested edge cases to pass CI undetected.
- **Proposed Fix:**
  1. Wire `spotbugs-maven-plugin`, `maven-checkstyle-plugin`, and `jacoco-maven-plugin` into parent `<build><plugins>`.
  2. Configure JaCoCo execution goal `check` with `<minimum>0.90</minimum>` line coverage and `<minimum>0.80</minimum>` branch coverage for `ev-validator` and `ev-planner-core`.
  3. Remove unread field `context` from `PlanValidator` or use it.

---

### 4.2 Battery Invariant Leaks (F-02 & F-03)
- **Description:**
  - In `BatteryFloorValidator.java:126`, after computing trip dropoff arrival SoC, the method executes:
    ```java
    currentSocWh = assign.departureAfterDropoffSocWh();
    ```
    It never validates that `departureAfterDropoffSocWh == arrivalAtDropoffSocWh - (any dropoff service energy)`.
  - In `BatteryFloorValidator.java:132-140`, for charging sessions:
    ```java
    if (session.startSocWh() < floorSocWh) {
      violations.add(new PlanViolation(...));
    }
    // Missing check: session.startSocWh() <= currentSocWh!
    currentSocWh = session.endSocWh();
    ```
- **Spec Reference:** `AGENTS.md` Section 1 (H1: "SoC never below 15% at any point, including deadhead").
- **Evidence:** Code inspection of `BatteryFloorValidator.java:110-145`. A test schedule created with an incoming SoC of 10,000 Wh and a `ChargingSession` declaring `startSocWh = 50,000 Wh` is accepted without any violation, artificially increasing battery energy by 40,000 Wh.
- **Impact on Phase 3:** If the planner has a bug or an aggressive ALNS operator that fabricates energy, the validator will approve the illegal plan, causing real-world EV stranding.
- **Proposed Fix:**
  1. Add assertion in `BatteryFloorValidator`:
     ```java
     if (assign.departureAfterDropoffSocWh() != arrivalAtDropoffSocWh) {
       violations.add(new PlanViolation(RuleId.H1_BATTERY_FLOOR, "Departure SoC after dropoff mismatch..."));
     }
     ```
  2. Add assertion for charging sessions:
     ```java
     if (session.startSocWh() > currentSocWh) {
       violations.add(new PlanViolation(RuleId.H1_BATTERY_FLOOR, "Charging session start SoC exceeds vehicle arrival SoC..."));
     }
     ```

---

### 4.3 Robustness Crashes on Malformed Inputs (F-04 & F-05)
- **Description:**
  - `ChargingCurve.validateInputs` throws `IllegalArgumentException` when `targetSocWh > batteryCapacityWh` or `startSocWh < 0`. `ChargingCurveValidator` calls this method directly without pre-checking input ranges.
  - `MatrixDistanceProvider.calculateDistanceDuration` throws `IllegalArgumentException` when a location pair is not present in its lookup matrix.
- **Spec Reference:** `AGENTS.md` Section 1 ("No plan is published... unless ev-validator passes it... On timeout, exception or bad data, send trips to backup. NEVER relax H1-H6") and Section 2.4 (Forbidden unhandled runtime crashes).
- **Evidence:**
  - In Adversarial Probe 13, setting `targetSocWh = 60,001 Wh` (capacity 60,000 Wh) resulted in:
    ```
    java.lang.IllegalArgumentException: targetSocWh (60001) must be within [0, 60000]
        at com.moveinsync.ev.domain.charging.ChargingCurve.validateInputs(ChargingCurve.java:197)
        at com.moveinsync.ev.domain.charging.ChargingCurve.calculateDurationSeconds(ChargingCurve.java:97)
        at com.moveinsync.ev.validator.ChargingCurveValidator.validateSessionDuration(ChargingCurveValidator.java:65)
    ```
  - In Adversarial Probe 15, an unknown location pair threw:
    ```
    java.lang.IllegalArgumentException: No distance entry for route from LOC-HUB-1 to LOC-UNKNOWN
        at com.moveinsync.ev.domain.distance.MatrixDistanceProvider.calculateDistanceDuration(MatrixDistanceProvider.java:31)
    ```
- **Impact on Phase 3:** In production, candidate plans from the planner or external drift inputs that contain out-of-bounds numbers will crash the validation process with HTTP 500 / Worker Thread crash instead of returning a clean rejected `ValidationReport` with reasons.
- **Proposed Fix:**
  - In `ChargingCurveValidator`, validate that `session.startSocWh() >= 0` and `session.endSocWh() <= vehicle.batteryCapacityWh()` before calling `ChargingCurve`. If invalid, emit a structured `PlanViolation`.
  - In `MatrixDistanceProvider`, return an `Optional<DistanceDuration>` or have `PlanValidator` catch missing route errors and emit an `H4_ROUTING_UNAVAILABLE` plan violation.

---

### 4.4 Charger Status Ignored (F-06)
- **Description:** `ChargerCapacityValidator` counts concurrent sessions across all chargers at a hub, but never checks if the individual charger assigned to a `ChargingSession` has `ChargerStatus.AVAILABLE`.
- **Spec Reference:** `docs/ARCHITECTURE.md` Section 3.1 & 7.2 (Charger failure re-plan: when a charger fails, active and future sessions on that charger must be moved).
- **Evidence:** In Adversarial Probe 8, scheduling a session on a charger with status `ChargerStatus.FAULTY` produced 0 violations.
- **Impact on Phase 3:** The planner could assign vehicles to broken chargers and the validator would declare the plan valid.
- **Proposed Fix:** In `ChargerCapacityValidator`, verify that `charger.status() == ChargerStatus.AVAILABLE`. If not, emit an `H2_CHARGER_UNAVAILABLE` violation.

---

### 4.5 Omitted Input Trips Detection (F-07)
- **Description:** `PlanValidator.validate(FleetPlan plan)` validates the internal consistency of assigned trips and unassigned trips inside `FleetPlan`, but does not take the set of original required trips. If a trip is completely dropped from `FleetPlan`, the validator cannot detect the loss.
- **Spec Reference:** `AGENTS.md` Section 8 ("Traceability: Why unassigned (reasons)").
- **Evidence:** In Adversarial Probe 7b, a required trip was dropped from both `assigned` and `unassigned` lists. The validator reported `isValid() == true` with 0 violations.
- **Impact on Phase 3:** A bug in the planner that accidentally drops trips from the schedule would go unnoticed.
- **Proposed Fix:** Overload `validate(FleetPlan plan, List<Trip> expectedTrips)` or require `FleetPlan` to be checked against the expected trip IDs, asserting that `assignedTrips U unassignedTrips == expectedTrips`.

---

## 5. Validator Completeness Matrix (Stage 4a)

| Hard Rule / Invariant | Checked? | Where Checked | Verdict | Evidence / Tests | Edge Cases Covered |
|:---|:---:|:---|:---:|:---|:---|
| **H1: Battery Floor (15%)** | Yes | `BatteryFloorValidator.java:27-145` | **PARTIAL** | `BatteryFloorValidatorTest`, Probe 1, Probe 2 | Exactly 15% valid; 1 Wh below invalid; initial below floor caught; deadhead caught. **Gap:** Accepts unverified departure SoC and plug start SoC. |
| **H2: Charger Capacity** | Yes | `ChargerCapacityValidator.java:27-106` | **PARTIAL** | `ChargerCapacityValidatorTest`, Probe 8 | Sweep-line algorithm exact; half-open $[s, e)$ verified; overlapping sessions caught. **Gap:** Ignores `ChargerStatus.FAULTY`. |
| **H3: Trip Timing** | Yes | `TripTimingValidator.java:32-88` | **OK** | `TripTimingValidatorTest`, Probe 6 | 1s late pickup caught; early arrival & wait valid; finish at latest finish valid; 1s past finish caught. |
| **H4: Single Cab / No Overlap** | Yes | `AssignmentValidator.java:38-124` | **OK** | `AssignmentValidatorTest`, Probe 7a, Probe 10, Probe 20 | Overlapping activities of cab caught; double assignment of trip across cabs caught; location discontinuity caught. |
| **H5: Reserve to Hub** | Yes | `BatteryFloorValidator.java:80-92` | **OK** | `PlanValidatorMutationTest:reserveToHubViolation`, Probe 5 | Energy to nearest hub calculated after each trip; breaches caught when remaining SoC drops below floor. |
| **H6: Charging Curve** | Yes | `ChargingCurveValidator.java:27-77` | **PARTIAL** | `ChargingCurveValidatorTest`, Probe 11, Probe 12 | Exact duration calculation verified; 1s short caught; 80% boundary verified. **Gap:** Unhandled crash on `targetSocWh > capacity`. |

---

## 6. Adversarial Probe Results (Stage 4c)

| Probe # | Description | Expected Behavior | Actual Behavior | Test Status | Verdict |
|:---:|:---|:---|:---|:---:|:---:|
| **1** | Exact 15% floor (9,000 Wh) vs 1 Wh below (8,999 Wh) | 9,000 Wh: valid (0 viol); 8,999 Wh: 2 viol | 9,000 Wh: 0 viol; 8,999 Wh: 2 viol | PASSED | **OK** |
| **2** | Deadhead energy causes drop below 15% floor | Violation reported | 2 violations reported (`H1_BATTERY_FLOOR_BREACH`) | PASSED | **OK** |
| **3** | Base consumption $\times$ traffic $\times$ AC multipliers | Consumption scaled up, floor breached | 1 violation reported | PASSED | **OK** |
| **4** | Initial SoC below 15% floor at horizon start | Violation reported immediately | 1 violation reported | PASSED | **OK** |
| **5** | Cab finishes trip with 16% SoC but needs 2% to reach hub | Reserve-to-hub violation reported | 1 violation reported (`H5_RESERVE_TO_HUB`) | PASSED | **OK** |
| **6** | Trip starts 1s late; arrives early; finishes at latest finish; +1s | 6a: viol; 6b: valid; 6c: valid; 6d: viol | 6a: 1 viol; 6b: 0 viol; 6c: 0 viol; 6d: 1 viol | PASSED | **OK** |
| **7a** | Same trip assigned to two different cabs | Double assignment violation reported | 1 violation reported (`H4_DOUBLE_ASSIGNMENT`) | PASSED | **OK** |
| **7b** | Trip omitted from both assigned and unassigned | Omission detected | 0 violations (omission not detected) | FAILED | **GAP** |
| **8a** | Sessions touching at boundary $[10, 20)$ and $[20, 30)$ | Valid (boundary belongs to second session) | 0 violations reported | PASSED | **OK** |
| **8b** | Sessions overlapping by 1s $[10, 21)$ and $[20, 30)$ | Overcapacity violation reported | 2 violations reported (`H2_CHARGER_OVERCAPACITY`) | PASSED | **OK** |
| **8c** | Booking session on `ChargerStatus.FAULTY` | Violation reported | 0 violations reported | FAILED | **GAP** |
| **9** | Zero-length and negative-length charging session | Construction rejected | `IllegalArgumentException` thrown by domain constructor | PASSED | **OK** |
| **10** | Two activities of one cab overlap by 1s | Cab overlap violation reported | 2 violations reported (`H4_CAB_ACTIVITY_OVERLAP`) | PASSED | **OK** |
| **11** | Claimed charging session duration is 1s short | Duration mismatch violation reported | 1 violation reported (`H6_CHARGING_DURATION_MISMATCH`) | PASSED | **OK** |
| **12** | Curve boundary: 79.99%, exactly 80%, 100%, crossing 80% | Exact integer seconds verified | 79.99% $\to$ 1s, 80% $\to$ 864s, 100% $\to$ 0s, cross $\to$ 1440s | PASSED | **OK** |
| **13** | Session `targetSocWh > batteryCapacityWh` | Structured violation reported | Crashed: `IllegalArgumentException` in `ChargingCurve` | FAILED | **BUG** |
| **14** | Plug power limited by `min(chargerKw, vehicleMaxKw)` | Correct effective power chosen | 150 kW plug + 50 kW cab $\to$ 50 kW; 30 kW plug $\to$ 30 kW | PASSED | **OK** |
| **15** | Unknown hub ID / unknown location in distance matrix | Structured violation reported | Unknown hub: 2 viol; Unknown loc: Crashed `IllegalArgumentException` | PARTIAL | **GAP** |
| **16** | Cross-tenant objects in one plan | Tenant isolation enforced | `FleetPlan` has `tenantId`; entities lack `tenantId` | NOTED | **SPEC_ISSUE** |
| **17** | Empty plan (0 cabs, 0 trips) | Valid, 0 violations | Valid, 0 violations | PASSED | **OK** |
| **18** | Arithmetic overflow on huge distance / duration | Safe integer math without wrap | Large distance computes positive energy without overflow | PASSED | **OK** |
| **19** | Tariff window on DST spring-forward (7h) & fall-back (9h) | Exact UTC window duration matches | London DST: 9h fall-back, 7h spring-forward | PASSED | **OK** |
| **20** | Location discontinuity (teleporting cab between trips) | Activity overlap / transit violation | 1 violation reported (`H4_LOCATION_DISCONTINUITY`) | PASSED | **OK** |
| **21** | Shuffled schedule input order determinism | Identical violation count | Exact same violation count reported | PASSED | **OK** |
| **22** | Scale performance: 300 cabs, 1,500 trips, 40 chargers | Validates in $< 1,000$ ms | Validated in **5 ms** | PASSED | **OK** |
| **23** | Concurrent validation of 100 plans across 8 threads | Thread-safe, identical reports | All 100 iterations identical, 0 concurrency errors | PASSED | **OK** |
| **24** | Single root cause produces deduplicated violation | No duplicate explosion | 1 violation reported | PASSED | **OK** |
| **25** | False positive check on tightly scheduled valid plan | Valid, 0 false positives | Valid, 0 violations | PASSED | **OK** |

---

## 7. Gate-Proof Table (Stage 3)

| Gate Tested | How Fault Was Injected | Detected By | Result | Gate Verdict |
|:---|:---|:---|:---|:---:|
| **ArchUnit: Validator $\to$ Planner Core** | Added `import com.moveinsync.ev.planner.core.DummyPlanner;` in `PlanValidator.java` | `ValidatorArchitectureTest` | Build failed with ArchUnit boundary violation | **PROVED** |
| **ArchUnit: Validator Dependencies** | Added `import org.springframework.stereotype.Service;` in `PlanValidator.java` | `ValidatorArchitectureTest` | Build failed with package rule violation | **PROVED** |
| **ArchUnit: Domain Dependencies** | Added `import org.springframework.stereotype.Component;` in `Vehicle.java` | `DomainArchitectureTest` | Build failed with framework dependency violation | **PROVED** |
| **Forbidden: `double` / `float`** | Injected `double x = 1.0;` into `BatteryFloorValidator.java` | `scripts/verify.sh` grep check | Script halted with error: forbidden double/float found | **PROVED** |
| **Forbidden: `System.out.println`** | Injected `System.out.println("leak");` into `PlanValidator.java` | `scripts/verify.sh` grep check | Script halted with error: forbidden `System.out` found | **PROVED** |
| **Forbidden: Empty Catch Block** | Injected `try {} catch (Exception e) {}` into `BatteryFloorValidator.java` | Checkstyle / ErrorProne | **PASSED UNNOTICED** (checkstyle inactive in verify) | **INACTIVE** |
| **Forbidden: `@Disabled` Test** | Injected `@Disabled` on test method in `PlanValidatorTest.java` | Grep / CI check | **PASSED UNNOTICED** (no grep for `@Disabled`) | **VACUOUS** |
| **Static Analysis: SpotBugs** | Existing unread field `context` in `PlanValidator.java` | SpotBugs plugin | **PASSED UNNOTICED in verify.sh**; failed on manual run | **INACTIVE** |
| **Coverage Thresholds: JaCoCo** | Line coverage at 82.0% in `ev-validator` ($< 90\%$) | JaCoCo check goal | **PASSED UNNOTICED in verify.sh**; failed on manual run | **INACTIVE** |

---

## 8. Mutation and Surviving Mutants

The mutation test suite in `PlanValidatorMutationTest.java` was evaluated against all six core invariants.

| Mutation Injected | Rule Tested | Caught by Validator? | Surviving? |
|:---|:---:|:---:|:---:|
| Shave 1 Wh from SoC at dropoff (below floor) | H1 | Yes (`H1_BATTERY_FLOOR_BREACH`) | **KILLED** |
| Shave 1 Wh from SoC during deadhead transit | H1 | Yes (`H1_BATTERY_FLOOR_BREACH`) | **KILLED** |
| Overlap two charging sessions by 1 second on single plug | H2 | Yes (`H2_CHARGER_OVERCAPACITY`) | **KILLED** |
| Shift trip pickup by $+1$ second (late start) | H3 | Yes (`H3_TRIP_TIMING`) | **KILLED** |
| Shift trip dropoff past latest allowed finish | H3 | Yes (`H3_TRIP_TIMING`) | **KILLED** |
| Assign same trip to two distinct vehicles | H4 | Yes (`H4_DOUBLE_ASSIGNMENT`) | **KILLED** |
| Overlap trip and charging session on same cab by 1s | H4 | Yes (`H4_CAB_ACTIVITY_OVERLAP`) | **KILLED** |
| Drop SoC below floor on post-trip return to hub | H5 | Yes (`H5_RESERVE_TO_HUB`) | **KILLED** |
| Reduce charging session duration by 1 second | H6 | Yes (`H6_CHARGING_DURATION_MISMATCH`) | **KILLED** |

### Mutation Score
- **Invariants covered:** 6/6 (100%)
- **Mutants killed:** 11/11 (100% of tested mutants killed)
- **Surviving mutants:** 0 in `PlanValidatorMutationTest`.
- *Note:* However, new mutation tests must be added for:
  1. Setting `assign.departureAfterDropoffSocWh() > arrivalSoc` (currently survives due to F-02).
  2. Setting `session.startSocWh() > incomingCabSoc` (currently survives due to F-03).
  3. Booking a `ChargerStatus.FAULTY` charger (currently survives due to F-06).

---

## 9. Assumptions Register

| Assumption | Found In | Documented in `DECISIONS.md`? | Risk if Wrong | Recommendation |
|:---|:---|:---:|:---|:---|
| Multi-tenancy is scoped at the `FleetPlan` envelope level, not per vehicle/trip record | `ev-domain` models | No | Multi-tenant leakage if entities from different tenants are mixed in one plan | Record in `docs/DECISIONS.md` or add `tenantId` to all domain entities. |
| Deadhead driving always concludes exactly at `pickupTs`, starting at `pickupTs - deadheadDuration` | `TripTimingValidator` | No | Overlap false-positives if a vehicle departs earlier to avoid traffic | Document deadhead timing convention in `docs/DECISIONS.md`. |
| Unassigned trips are optional in `FleetPlan` | `FleetPlan` | No | Validator cannot verify whether unassigned trips were genuinely unschedulable or accidentally lost | Add expected trips parameter to validator. |
| Hub coordinates are identical for all chargers at that hub | `Hub`, `Charger` | Yes | Zero (standard domain definition) | Keep as-is. |
| Plug power is determined by `min(chargerKw, vehicleMaxKw)` | `ChargingCurve` | Yes | Zero (matches physical reality) | Keep as-is. |

---

## 10. Forward-Compatibility Verdicts (Stage 6)

| Subsystem / Requirement | Ready for Phase 3? | Verdict | Analysis |
|:---|:---:|:---:|:---|
| **ALNS State Representation** | Yes | **PASS** | `VehicleSchedule`, `TripAssignment`, and `ChargingSession` are immutable, clean value objects. State copying for destroy/repair operators will be straightforward and thread-safe. |
| **Flow Re-timer** | Yes | **PASS** | `ChargingCurve` provides deterministic duration functions; integer-second resolution enables integer linear programming or min-cost network flow re-timing. |
| **Re-plan Ladder** | Partial | **CONDITIONAL** | Needs F-04 and F-05 resolved so that fast-path Tier 0/1 heuristics that test partial or speculative schedules do not crash on invalid intermediate bounds. |
| **Persistence (Phase 4)** | Yes | **PASS** | Clear record structures with explicit IDs (`VehicleId`, `TripId`, `HubId`, `ChargerId`) match relational tables. |
| **Telemetry Ingestion** | Yes | **PASS** | `Vehicle` model supports dynamic current SoC and degradation modeling. |

---

## 11. Verified OK (Positive Evidence)

1. **Integer Arithmetic Rigor:**
   - Command: `grep -rnE "\b(float|double|BigDecimal)\b" ev-domain/src/main ev-validator/src/main`
   - Result: 0 matches in constraint logic. All energy is in Wh, distance in metres, time in epoch seconds (UTC), and money in micro-units.
2. **Conservative Rounding:**
   - Energy calculations round UP (`Math.divideExact` / integer ceiling logic `(a + b - 1) / b`).
   - Time calculations round UP (`Duration` division rounds up).
   - Capacity calculations round DOWN.
3. **Independent Architectural Isolation:**
   - Command: `./mvnw test -pl ev-validator -Dtest=ValidatorArchitectureTest`
   - Result: Passed. `ev-validator` has 0 dependencies on `ev-planner-core`, Spring, or persistence libraries.
4. **Validation Scale Performance:**
   - Adversarial Probe 22 tested a plan with **300 cabs, 1,500 trips, and 40 chargers**.
   - Result: Validated in **5 milliseconds**, well below the 1,000 ms ceiling (and 30 s end-to-end target).
5. **Thread Safety & Reentrancy:**
   - Adversarial Probe 23 validated 100 concurrent requests across 8 threads on a shared validator instance.
   - Result: 100% identical reports, 0 exceptions, 0 race conditions.
6. **DST & Midnight Crossing Handling:**
   - `UtcTariffWindow` and `TariffWindow` tested on British Summer Time transition days.
   - Result: Exactly 7 hours for spring-forward night, 9 hours for fall-back night, and seamless midnight-crossing tariff transitions.

---

## 12. Proposed Fix Plan

The following ordered plan addresses all findings. **No fixes will be applied until approved by the user.**

### Priority 1: BLOCKERS (Must be fixed before Phase 3 code is written)
1. **Fix 1: Activate static analysis and coverage gates in POM and verify script**
   - *Message:* `fix(build): activate checkstyle, spotbugs, and jacoco gates in verify phase`
   - *Scope:* `pom.xml`, `scripts/verify.sh`, `PlanValidator.java` (remove unread field `context`).
   - *Effort:* 45m.
2. **Fix 2: Prevent phantom energy creation and plug SoC inflation in BatteryFloorValidator**
   - *Message:* `fix(validator): enforce strict energy balance at dropoff and plug arrival`
   - *Scope:* `ev-validator/src/main/java/com/moveinsync/ev/validator/BatteryFloorValidator.java`.
   - *Effort:* 30m.
3. **Fix 3: Guard ChargingCurveValidator against out-of-bounds SoC crashes**
   - *Message:* `fix(validator): emit plan violation on invalid charging curve SoC bounds`
   - *Scope:* `ev-validator/src/main/java/com/moveinsync/ev/validator/ChargingCurveValidator.java`.
   - *Effort:* 30m.

### Priority 2: MAJORS (Validator Completeness & Robustness)
4. **Fix 4: Check charger operational status in ChargerCapacityValidator**
   - *Message:* `fix(validator): reject charging sessions scheduled on non-available chargers`
   - *Scope:* `ev-validator/src/main/java/com/moveinsync/ev/validator/ChargerCapacityValidator.java`.
   - *Effort:* 30m.
5. **Fix 5: Handle missing distance routes gracefully with structured plan violations**
   - *Message:* `fix(validator): emit routing violation instead of crashing on missing distance matrix entry`
   - *Scope:* `ev-validator/src/main/java/com/moveinsync/ev/validator/TripTimingValidator.java`, `BatteryFloorValidator.java`.
   - *Effort:* 45m.
6. **Fix 6: Deterministic violation ordering and input trip completeness check**
   - *Message:* `fix(validator): ensure deterministic violation sorting and detect omitted trips`
   - *Scope:* `ev-validator/src/main/java/com/moveinsync/ev/validator/PlanValidator.java`, `ChargerCapacityValidator.java`.
   - *Effort:* 45m.

### Priority 3: MINORS & Cleanups
7. **Fix 7: Split long methods and add unit test for ConstantConsumptionModel**
   - *Message:* `refactor(validator): decompose methods exceeding 40 lines and add consumption tests`
   - *Scope:* `AssignmentValidator.java`, `TripTimingValidator.java`, `ConstantConsumptionModelTest.java`.
   - *Effort:* 30m.
8. **Fix 8: Record multi-tenancy and deadhead timing decisions in DECISIONS.md**
   - *Message:* `docs: record architectural decisions for plan-level tenancy and deadhead timing`
   - *Scope:* `docs/DECISIONS.md`.
   - *Effort:* 15m.

---

> [!IMPORTANT]
> **Awaiting Human Approval**: In accordance with the Rules of Engagement, no code changes or fixes have been applied to tracked files. Please review the findings and approve the Proposed Fix Plan to begin remediation.

