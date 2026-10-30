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
