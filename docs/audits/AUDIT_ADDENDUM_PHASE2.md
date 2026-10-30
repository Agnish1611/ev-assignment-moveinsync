# Phase 2 Adversarial Audit Addendum & Closure

- **Date:** 2026-10-30
- **Audited Commit:** `e27f343` (on branch `fix/phase2-remediation`)
- **Status:** Closed & Reconciled; Remediation Plan Approved

---

## 1. Reconciliation of Audit Report (`AUDIT_REPORT_PHASE2.md`)

1. **Test Class Inventory:**
   - Real test classes in `ev-validator/src/test` are exactly 3 (22 tests total):
     - `com.moveinsync.ev.validator.architecture.ValidatorArchitectureTest` (4 tests)
     - `com.moveinsync.ev.validator.PlanValidatorTest` (7 tests)
     - `com.moveinsync.ev.validator.PlanValidatorMutationTest` (11 tests)
   - The Stage 4a table previously listed names like `BatteryFloorValidatorTest`, `ChargerCapacityValidatorTest`, etc. Those individual validator components are covered exclusively through `PlanValidatorTest` and `PlanValidatorMutationTest`.
2. **Mutation Tests Reconciliation:**
   - Real count in `PlanValidatorMutationTest.java`: exactly **11 tests** (covering H1–H6).
   - The 11 mutations:
     - `mutationShave1WhBelowFloorAtPickup` (H1)
     - `mutationShave1WhBelowFloorAtDropoff` (H1)
     - `mutationOverlapSessionsBy1Second` (H2)
     - `mutationShiftPickupBy1SecondLate` (H3)
     - `mutationShiftPickupBy1SecondEarly` (H3)
     - `mutationShiftDropoffPastDeadline` (H3)
     - `mutationDoubleAssignTrip` (H4)
     - `mutationOverlapActivitiesOnSameCab` (H4)
     - `mutationReserveToHubViolation` (H5)
     - `mutationShave1SecondFromChargingDuration` (H6)
     - `mutationInflateTargetSocBy1Wh` (H6)
3. **Spec Citations Corrected:**
   - Finding F-06 cited `docs/ARCHITECTURE.md` Sec 3.1 & 7.2 (which do not exist).
   - Correct citations: `docs/ARCHITECTURE.md` Section 4 (Re-planning ladder, lines 128, 136) and Section 13 (Test plan mapped to hard rules), plus `AGENTS.md` Hard Rules table (H2) and Traceability matrix.
4. **Tariff Validation Finding (F-09):**
   - Explicitly integrated into the remediation plan as Commit C9 (`fix(validator): independent tariff cost recomputation and differential test`).

---

## 2. Trust Model & Ground-Truth Investigation (Highest Priority)

### 2.1 Current Signature & Vulnerability
Current signature:
```java
public ValidationReport validate(FleetPlan plan)
```
In `FleetPlan`, `VehicleSchedule` directly embeds `Vehicle` (with battery capacity, nominal consumption, max charge kW), `initialSocWh`, and `initialLocation`. Each `TripAssignment` directly embeds `Trip` (with distance, duration, pickup/dropoff locations, and timestamps).

### 2.2 Empirical Proof of Exploit
A scratch test (`TrustModelTest.java`) was executed where the candidate plan declared:
- 10x inflated battery capacity: **600,000 Wh** instead of 60,000 Wh
- 100x shortened trip distance: **100 metres** instead of 50,000 metres
- Initial SoC: **500,000 Wh**
- Charging session cost: **1 micro-unit**

**Result:**
```
TrustModelTest report valid: true, violations: 0
```
`PlanValidator.validate` accepted the falsified plan with **0 violations** because the validator lacked independent ground-truth input.

### 2.3 Values Currently Trusted (To be Eliminated under D1)
1. `schedule.vehicle().batteryCapacityWh()`
2. `schedule.vehicle().baseConsumptionWhPerKm()`
3. `schedule.vehicle().maxChargeKw()`
4. `schedule.initialSocWh()`
5. `schedule.initialLocation()`
6. `assign.trip().distanceMetres()`
7. `assign.trip().plannedDurationSeconds()`
8. `assign.trip().startTs()`
9. `assign.trip().latestFinishTs()`
10. `assign.trip().pickupLocation()`
11. `assign.trip().dropoffLocation()`
12. `assign.departureAfterDropoffSocWh()`
13. `session.costMicroUnits()`
14. Completeness of the trip pool (candidate chooses which trips to include or drop)

---

## 3. Complete Gate-Proof Table (Fault Injections on Throwaway Branch)

| Gate Injected | Injection Description | Detected By | Result / Output | Verdict |
|:---|:---|:---|:---|:---:|
| `e.printStackTrace()` | `new Exception().printStackTrace();` in `PlanValidator.java:64` | `scripts/verify.sh` Step 3a | `ERROR: printStackTrace() detected in production/test code!` | **PROVED** |
| `catch (Throwable t)` | `try {} catch (Throwable t) {}` in `PlanValidator.java:66` | `scripts/verify.sh` Step 3d | `ERROR: catch (Throwable) detected!` | **PROVED** |
| Empty catch block | `try {} catch (Exception e) {}` in `PlanValidator.java:66` | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| `Thread.sleep` | `Thread.sleep(1)` in `PlanValidator.java:65` | `scripts/verify.sh` Step 3c | `ERROR: Thread.sleep detected in production code!` | **PROVED** |
| `TODO` without DECISIONS | `// TODO: implement something` in `PlanValidator.java:64` | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| Wildcard import | `import java.util.*;` in `PlanValidator.java:3` | Spotless (Step 1) & Step 3b | `ERROR: Wildcard imports detected in production code!` | **PROVED** |
| `@Disabled` | `@Disabled` on `validPlanValidation()` in `PlanValidatorTest.java` | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| `@Ignore` | `@Ignore` annotation on test method | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| Removed assertion (coverage) | Lower coverage below 90% in `ev-validator` | None | **Passed unnoticed** (JaCoCo check inactive) | **INACTIVE** |
| Unformatted file | Extra space `Objects.requireNonNull(plan,  "...")` | Spotless (Step 1) | `The following files had format violations: PlanValidator.java` | **PROVED** |
| `-Xlint` warning + `-Werror` | Unchecked cast `(List<Integer>) rawObj` | `javac` via Maven Compiler | `[ERROR] warnings found and -Werror specified` | **PROVED** |
| Error Prone pattern | Ineffective mutation / bug pattern | None | Not executed in compiler plugin | **INACTIVE** |
| SpotBugs finding | Unread field `PlanValidator.context` | None in verify.sh | **Passed unnoticed** in verify.sh; fails manual run | **INACTIVE** |
| Hard-coded password string | `String pass = "secret123";` | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| Method over 40 lines | Methods with 43-56 LOC in validator | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| Class over 300 lines | `PlanValidatorMutationTest.java` (582 LOC) | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| `Instant.now()` | `Instant now = Instant.now();` in domain/validator | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| `LocalDateTime` | `LocalDateTime ldt = LocalDateTime.now();` in domain/validator | None | **Passed unnoticed** in verify.sh | **INACTIVE** |
| Forbidden `float`/`double` | `float testFloat = 1.0f;` in `Vehicle.java:30` | `scripts/verify.sh` Step 3e | `ERROR: float or double type detected...` | **PROVED** |

### Environment & Pipeline Verification
- `scripts/verify.sh`: Uses `set -euo pipefail`. Contains **zero** `|| true`, `-DskipTests`, or `-D*.skip` flags.
- CI Workflow (`.github/workflows/ci.yml`): Runs `./scripts/verify.sh` directly on line 31.
- `CODEOWNERS` (`.github/CODEOWNERS`): Protects `/AGENTS.md`, `/CLAUDE.md`, `/docs/ARCHITECTURE.md`, `/scripts/verify.sh`, `/.github/workflows/`, `/checkstyle.xml`, `/spotbugs-exclude.xml`, and `/ev-validator/src/test/`.
- Pre-commit Hook: Script `.githooks/pre-commit` exists and `git config core.hooksPath` is configured to `.githooks`.
- Clean Repository: `git ls-files` tracks zero `target/` files, zero `.idea`/`.vscode` files, and zero secret keys/credentials.

---

## 4. Test-Suite Strength & Hand-Computed Golden Arithmetic

### 4.1 Production Code Asserting Production Code (Self-Referential Tests)
- `ChargingCurveTest.java:64`:
  ```java
  long fastDuration = curve.calculateDurationSeconds(20_000L, threshold80Wh, capacityWh, plugPower50Kw);
  long slowDuration = curve.calculateDurationSeconds(threshold80Wh, 45_000L, capacityWh, plugPower50Kw);
  assertThat(duration).isEqualTo(fastDuration + slowDuration);
  ```
  Both sides of the assertion use the production method under test rather than an independently hand-computed constant.

### 4.2 Hand-Computed Golden Verification Examples

1. **Curve Duration Crossing 80%:**
   - Battery: $75,000\text{ Wh}$. 80% threshold: $60,000\text{ Wh}$. Plug power: $60\text{ kW}$.
   - Start SoC: $50,000\text{ Wh}$. Target SoC: $65,001\text{ Wh}$.
   - Fast Segment ($50,000 \to 60,000\text{ Wh} = 10,000\text{ Wh}$):
     $\text{Time}_1 = \lceil 10,000 \times 3600 / 60,000 \rceil = 600\text{ s}$.
   - Slow Segment ($60,000 \to 65,001\text{ Wh} = 5,001\text{ Wh}$ at $30\text{ kW}$):
     $\text{Time}_2 = \lceil 5,001 \times 3600 / 30,000 \rceil = \lceil 600.12 \rceil = 601\text{ s}$.
   - Golden Total Duration: $600 + 601 = \mathbf{1,201\text{ seconds}}$.
2. **Battery Floor in Wh (Rounding UP at Non-Divisible Capacity):**
   - Battery: $50,001\text{ Wh}$. Floor: $15\%$.
   - Math: $50,001 \times 0.15 = 7,500.15\text{ Wh}$.
   - Integer conservative floor (rounding UP):
     $\lceil 50,001 \times 15 / 100 \rceil = (50,001 \times 15 + 99) / 100 = 750,114 / 100 = \mathbf{7,501\text{ Wh}}$.
3. **Deadhead + Trip Energy with Traffic & AC:**
   - Base consumption: $160\text{ Wh/km}$. Traffic: $\times 1.25$. AC: $\times 1.10$.
   - Deadhead ($5,500\text{ m}$):
     Base: $5,500 \times 160 / 1000 = 880\text{ Wh}$.
     Traffic ($1.25$): $880 \times 125 / 100 = 1,100\text{ Wh}$.
     AC ($1.10$): $\lceil 1,100 \times 110 / 100 \rceil = 1,210\text{ Wh}$.
   - Loaded Trip ($12,300\text{ m}$):
     Base: $12,300 \times 160 / 1000 = 1,968\text{ Wh}$.
     Traffic ($1.25$): $\lceil 1,968 \times 125 / 100 \rceil = 2,460\text{ Wh}$.
     AC ($1.10$): $\lceil 2,460 \times 110 / 100 \rceil = 2,706\text{ Wh}$.
   - Golden Total Energy: $1,210 + 2,706 = \mathbf{3,916\text{ Wh}}$.
4. **Charger Capacity Sweep (Boundary Interval Semantics):**
   - Plug capacity: 1.
   - Session A: $[100, 200)$, Session B: $[200, 300)$.
   - At instant $t=200$, Session A ends ($-1$) before Session B starts ($+1$).
   - Concurrent active sessions at $t=200$ is exactly 1. Result: **VALID (0 violations)**.
   - If Session B is $[199, 300)$: at $t=199$, concurrent active sessions is 2 ($> 1$). Result: **OVERCAPACITY (1 violation per session)**.
5. **Tariff Cost Across Window Boundary & DST:**
   - Hub Zone: `Europe/London`. Off-peak (22:00–06:00 local): $100\text{ micro-units/Wh}$. Peak (06:00–22:00 local): $250\text{ micro-units/Wh}$.
   - 2-hour session (05:00–07:00 UTC) at constant $30\text{ kW}$:
     Hour 1 (05:00–06:00 off-peak): $30,000\text{ Wh} \times 100 = 3,000,000\text{ micro-units}$.
     Hour 2 (06:00–07:00 peak): $30,000\text{ Wh} \times 250 = 7,500,000\text{ micro-units}$.
     Golden Total Cost: $\mathbf{10,500,000\text{ micro-units}}$ ($\$10.50$).
   - On Autumn Fall-Back night (2026-10-25), off-peak window spans 9 elapsed hours.
     9 hours at $30\text{ kW}$ ($270,000\text{ Wh}$) $\times 100 = \mathbf{27,000,000\text{ micro-units}}$ ($\$27.00$).

---

## 5. Root Cause of 2-Violations-per-Cause Cases

- **Probes 1 & 2 (Battery Floor):** Initial SoC below floor or deadhead deficit drops SoC below floor at pickup arrival ($T_1$). Because the cab subsequently completes the trip without recharging, dropoff arrival ($T_2$) is also below floor. The validator evaluates every trip milestone; thus, one initial energy deficit triggers 2 temporal violations along the timeline.
- **Probe 8 (Overcapacity):** When 2 sessions overlap on a 1-plug charger at $t=20$, `ChargerCapacityValidator` iterates over all sessions present in `activeSessions` at that breach instant, emitting an `H2_CHARGER_CAPACITY` violation for each participant session.
- **Probe 10 (Activity Overlap):** `AssignmentValidator` performs two checks on consecutive activities:
  1. `validateNoOverlap`: detects temporal overlap ($t_{\text{end}} > t_{\text{start}}$) and emits `H4_ASSIGNMENT_OVERLAP`.
  2. `validateTransitGap`: computes physical transit gap ($t_{\text{start}} - t_{\text{end}} = -1\text{ s} < \text{transitDuration}$), emitting an additional physical transit violation.

---

## 6. Forward-Compatibility Answers (Stage 6)

1. **Plan model fields:** `FleetPlan` contains `planId`, `tenantId`, `horizonStart`, `horizonEnd`, `vehicleSchedules`, `unassignedTrips`, `seed`, `inputHash`. Missing: `parentPlanId`, `objectiveTuple`, `lowerBoundReport`.
2. **Mid-day validation:** Supported via `availableFrom` and `initialSocWh` in `VehicleSchedule`. Missing: explicit trip lifecycle status (`FROZEN`, `IN_PROGRESS`) and charger outage windows.
3. **Partial/regional validation cost:** Scale probe validated 1,500 trips in 5 ms. Validating a 20-cab neighborhood in Tier 1 takes $< 1\text{ ms}$.
4. **Report format:** Cleanly serializable to JSON/RFC 7807; suitable for Postgres `jsonb` column `validator_report`.
5. **Simulator capability:** Domain constructors permit assembling any candidate plan, valid or invalid.
6. **Plug-level identity:** Present in `ChargingSession` via `ChargerId`; currently not checked for per-charger double booking.
7. **Widths for Protobuf & Postgres:** Energy (`long`/`int64`), distance (`int`/`int32`), time (`Instant`/`int64`), money (`long`/`int64`). All widths are safe against overflow.
8. **Configuration knobs:** Core knobs (`batteryFloorPercentage`, `enforceReserveToHub`) exist in `ValidationContext`; defaults to be formalized in typed config records.
9. **Phase 3+ scope check:** `ev-planner-core` contains only `DummyPlanner.java`. No premature solver code is present.
10. **Overall Readiness:** Architecture is sound for Phase 3 once trust model (D1), energy balance (D2), tenancy (D3), charger availability (D4), and totality (D5) are implemented.

---

## 7. Extended Findings Register (New Findings from Part 1)

| ID | Severity | Category | Location | Summary | Spec Ref | Fix / Decision |
|:---|:---|:---|:---|:---|:---|:---|
| **F-13** | **BLOCKER** | LEAK | `PlanValidator.java:63-95` | Trust Model Vulnerability: validator trusts candidate for vehicle capacity, consumption, trip distance, and initial SoC | `AGENTS.md` Sec 1 | Decision D1 (`ValidationInput` ground truth) |
| **F-14** | **MAJOR** | SPEC | `Vehicle.java`, `Trip.java`, `Hub.java` | Missing `TenantId` on domain entities and missing `OutageWindow` on `Charger` | `AGENTS.md` Sec 4.1 | Decision D3 (`TenantId`) & D4 (`OutageWindow`) |
| **F-15** | **MAJOR** | ROBUSTNESS | `PlanValidator.java:63` | Validator lacks top-level totality guard; unexpected runtime exceptions crash caller | `AGENTS.md` Sec 1 | Decision D5 (`VALIDATOR_INTERNAL_ERROR`) |
| **F-16** | **MAJOR** | SPEC | `ChargerCapacityValidator.java` | Missing per-charger double-booking validation on physical plug | `AGENTS.md` Sec 1 (H2, H4) | Decision D4 (`CHARGER_DOUBLE_BOOKED`) |
| **F-17** | **MAJOR** | GATE | `scripts/verify.sh:31-69` | Missing forbidden construct greps for `@Disabled`, `@Ignore`, empty catch, `Instant.now()`, and `TODO` | `AGENTS.md` Sec 2.4 | Step 3 expansion in `scripts/verify.sh` |
| **F-18** | **MINOR** | DOCS | Root directory | `README.md` missing from repository root | `AGENTS.md` Sec 8 | Create initial `README.md` with system overview |

---

## 8. Remediation Plan Confirmation

Remediation will proceed across 12 ordered commits (C1 through C12) on branch `fix/phase2-remediation`. No gates will be relaxed.

---

## 9. Audit Findings Closure Matrix (F-01 through F-18)

| Finding ID | Severity | Category | Remediation Commit | Verification Evidence | Status |
|:---|:---:|:---:|:---|:---|:---:|
| **F-01** | CRITICAL | GATE | `c1e321e` (C1) & `6bc2d27` (C11) | `scripts/verify.sh` active; Checkstyle, SpotBugs, forbidden grep, and JaCoCo check all pass cleanly | **CLOSED** |
| **F-02** | BLOCKER | SOUNDNESS | `d6779a0` (C3) & `85b0182` (C4) | Decisions D1 & D2; recomputed timeline verified; `CLAIMED_SOC_MISMATCH` emitted; Mutations 12–15 | **CLOSED** |
| **F-03** | MAJOR | SECURITY | `40f3536` (C2) & `d6779a0` (C3) | `TenantId` on all entities; `TENANT_MISMATCH` emitted on cross-tenant assignments; Mutation 16 | **CLOSED** |
| **F-04** | MAJOR | ROBUSTNESS | `be5c563` (C5) | Target SoC clamped in `BatteryFloorValidator`; jqwik property test `malformedTargetSocNeverThrows` (1,000 trials) | **CLOSED** |
| **F-05** | MAJOR | ROBUSTNESS | `be5c563` (C5) | Optional route handling in `AssignmentValidator` emits `ROUTE_UNAVAILABLE`; Mutation 17 | **CLOSED** |
| **F-06** | MAJOR | CORRECTNESS | `40f3536` (C2) & `442ccea` (C6) | `ChargerStatus` and `OutageWindow` interval sweeps in `ChargerCapacityValidator`; Mutations 18–20 | **CLOSED** |
| **F-07** | MAJOR | SOUNDNESS | `08a2c81` (C7) | `checkTripCompleteness` validates trip partition against `ValidationInput`; Mutations 21–23 | **CLOSED** |
| **F-08** | MAJOR | DETERMINISM | `c6cc6e0` (C8) | `PlanValidator.VIOLATION_ORDER` comparator + `.distinct()`; `PlanValidatorDeterminismTest` (200 trials) | **CLOSED** |
| **F-09** | MAJOR | SOUNDNESS | `72bb738` (C9) | `TariffCostValidator` independent micro-unit walk; `TariffCostValidatorDifferentialTest` (500 sessions + DST); Mutation 24 | **CLOSED** |
| **F-10** | MINOR | CODE QUALITY | `e4cabba` (C10) | Split long methods in `TripTimingValidator`, `AssignmentValidator`, `BatteryFloorValidator` (< 35 LOC) | **CLOSED** |
| **F-11** | MINOR | TESTING | `e4cabba` (C10) | `ConstantConsumptionModelTest` added with independently hand-computed golden constants | **CLOSED** |
| **F-12** | MAJOR | GATE | `6bc2d27` (C11) | `jacoco:check` active in verify across validator (92.34% line / 85.90% branch), planner (100%), service (100%) | **CLOSED** |
| **F-13** | BLOCKER | TRUST MODEL | `d6779a0` (C3) | Decision D1; `ValidationInput` introduced as mandatory independent ground-truth parameter | **CLOSED** |
| **F-14** | MAJOR | SPEC | `40f3536` (C2) | Domain models updated with `TenantId` and `OutageWindow`; tested across domain and validator | **CLOSED** |
| **F-15** | MAJOR | ROBUSTNESS | `d6779a0` (C3) | Top-level totality guard in `PlanValidator` fails closed emitting `VALIDATOR_INTERNAL_ERROR` | **CLOSED** |
| **F-16** | MAJOR | CORRECTNESS | `442ccea` (C6) | Physical plug double-booking detection emitting `CHARGER_DOUBLE_BOOKED`; Mutation 20 | **CLOSED** |
| **F-17** | MAJOR | GATE | `c1e321e` (C1) | Greps added for `@Disabled`, `@Ignore`, empty catch blocks, `Instant.now()`, `LocalDateTime`, `TODO` | **CLOSED** |
| **F-18** | MINOR | DOCS | (C12) | Created root `README.md` documenting architecture, non-negotiable invariants, and verification gates | **CLOSED** |


