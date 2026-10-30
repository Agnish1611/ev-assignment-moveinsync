# Decisions Log

Decisions not covered in `docs/ARCHITECTURE.md` are recorded here (one line: decision, reason).

| Date | Decision | Rationale |
|---|---|---|
| 2026-10-30 | Maven multi-module scaffold targeting Java 21 | Modular architecture and compilation target as specified in architecture |
| 2026-10-30 | Use latest stable dependency and plugin versions | Spring Boot 3.5.16, ArchUnit 1.5.1, jqwik 1.10.2, Testcontainers 2.0.5, JaCoCo 0.8.16, SpotBugs 4.10.5.0 |
| 2026-10-30 | Explicit `<relativePath>../pom.xml</relativePath>` across all child POMs | Prevents IDE language server resolution errors when inspecting submodule POMs |
| 2026-10-30 | Use `${maven.multiModuleProjectDirectory}` for checkstyle and spotbugs configs | Guarantees deterministic configuration resolution from both project root and submodule directories |
| 2026-10-30 | ChargingCurve piecewise segments: 0-80% at 100% plug power, 80-100% at 50% power | Matches two-segment curve specification with conservative integer rounding |
| 2026-10-30 | Consumption multipliers represented as integer basis points (10,000 = 1.0x) | Ensures strict integer arithmetic and zero float/double usage in ev-domain |
| 2026-10-30 | TariffWindow converts to UTC via ZonedDateTime over shift date | Guarantees DST transition and midnight-crossing shifts resolve to unambiguous UTC Instants |
| 2026-10-30 | Charger capacity validated via continuous event-sweep with [start, end) intervals | Decouples validator from discrete 60s planner grid and prevents concurrent plug oversubscription |
| 2026-10-30 | Decompose validator into modular check components per invariant (H1-H6) | Avoids god classes, maintains single responsibility, and guarantees methods stay under 40 lines |
| 2026-10-30 | Domain entities permit unvalidated candidate states for validator inspection | Allows candidate plans with timing or overlap issues to be evaluated and reported rather than throwing in constructors |
| 2026-10-30 | D1: Trust model via independent `ValidationInput` ground truth | Validator must never trust candidate claims for capacities, distances, tariffs, power, or charger counts |
| 2026-10-30 | D2: Recompute and strictly verify SoC claims (`CLAIMED_SOC_MISMATCH`) | Energy conservation requires exact integer Wh matching between candidate claims and recomputed timeline |
| 2026-10-30 | D3: Tenancy enforcement on all domain entities (`TENANT_MISMATCH`) | Prevents cross-tenant entity leakage across vehicles, trips, hubs, chargers, and unassigned trips |
| 2026-10-30 | D4: Charger availability, outage windows, and plug double-booking checks | Enforces physical charger status, half-open outage intervals, and single-session-per-plug invariant |
| 2026-10-30 | D5: Totality guard in `PlanValidator` failing closed (`VALIDATOR_INTERNAL_ERROR`) | Validator must never crash caller on unexpected runtime exceptions; fails closed with invalid report |
| 2026-10-30 | D6: Deterministic violation ordering and deduplication | Comparator sorts by rule, vehicle, trip, hub, time, message to guarantee identical outputs across permutations |
| 2026-10-30 | D7: Battery floor is inclusive (`SoC >= floorWh`) | Exactly 15% (e.g. 7,501 Wh on 50,001 Wh battery) is VALID; shaving 1 Wh (7,500 Wh) is strictly INVALID (H1) |
| 2026-10-30 | D8: Deadhead factor application | Traffic basis points and AC factors apply to deadhead travel identically to loaded trip segments |
| 2026-10-30 | D9: Idle power dissipation convention | Default 0 W; represented as non-negative integer Watts dissipated continuously during stationary idle gaps |
| 2026-10-30 | D10: Charging horizon containment | All activities and charging sessions must be fully contained within the plan horizon `[horizonStart, horizonEnd]` |
| 2026-10-30 | D11: Consumption rounding order | Base distance energy rounded UP to Wh, then traffic basis points applied and rounded UP, then AC basis points applied and rounded UP |
| 2026-10-30 | D12: Outward slot quantisation vs continuous validation | Planners may quantise to 60s discrete grid rounding outwards; independent validator evaluates exact continuous epoch seconds |
| 2026-10-30 | D13: Hierarchical objective tuple ordering | Lexicographic priority: (1) unassigned trips, (2) total energy & operational cost |
| 2026-10-30 | D14: Planning robustness margin separation | Planner uses conservative 17-18% effective battery floor; validator strictly enforces the true 15% physical floor |
| 2026-10-30 | D15: Segmented charging power model | Two-segment charging curve: fast rate (100% plug power) up to 80% capacity, stepped-down rate (50% power) above 80% |
| 2026-10-30 | D16: Strict ground truth vs lenient candidate plan model | Ground-truth types in ValidationInput (Vehicle, Trip, Hub, Charger, TariffWindows) strictly validate invariants and fail fast on invalid parameters; candidate types (FleetPlan, VehicleSchedule, TripAssignment, ChargingSession) remain lenient so PlanValidator can inspect flawed plans and return diagnostic reports without throwing constructor exceptions |
| 2026-10-30 | D17: No implicit tenant on ground-truth types | Vehicle, Trip, Hub, and Charger public constructors and factories require an explicit tenant; a sentinel tenant is forbidden in src/main so it cannot bypass TENANT_MISMATCH |
| 2026-10-30 | verify.sh grep gates fail closed when grep exits >= 2 | A missing or unreadable scan path must not be reported as a clean scan |
| 2026-10-30 | Leave `a1c9be4` in history | That commit fails test compilation; rewriting it is forbidden and the branch tip already passes `scripts/verify.sh` |
| 2026-10-30 | Keep PIT `STRONGER` and do not exclude validator classes to chase 90% | The Phase 2 gate is JaCoCo plus named mutation tests; a higher PIT score from a weaker mutator set would hide survivors rather than kill them |
| 2026-10-30 | D18: Planning floor is 17 or 18 percent, lock window is 900 seconds, change penalty is 1_000_000 micros | The planner stays above the validator's 15 percent floor; one changed assignment must save more than 1 currency unit before a replan prefers it |
