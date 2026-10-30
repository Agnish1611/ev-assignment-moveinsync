# EV Fleet Charging & Trip Assignment Service

Production-grade EV fleet charging and trip assignment optimization platform for MoveInSync Case Study 6 (Advanced Track).

## Architecture & Modules

The platform is designed in strict accordance with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md):

- **`ev-domain`**: Immutable domain value objects and domain logic (pure Java, zero framework/database dependencies).
- **`ev-validator`**: Independent validation engine enforcing hard invariants H1–H6 against ground-truth `ValidationInput`. Depends **only** on `ev-domain`.
- **`ev-planner-core`**: Optimization engine (ALNS, flow re-timer, lower bounds, counterfactual diagnosis) in pure Java.
- **`ev-service`**: Spring Boot REST application, Kafka event streaming, persistence with PostgreSQL RLS, and Keycloak RBAC.
- **`ev-sim`**: Deterministic scenario generation, battery drift injection, and load driving.
- **`ev-bench`**: JMH micro-benchmarks and k6 performance test assets.

## Non-Negotiable Invariants

All plans must satisfy invariants H1–H6 prior to publication or persistence:

| ID | Invariant | Description | Enforced By |
|:---|:---|:---|:---|
| **H1** | Battery Floor | State of Charge never drops below 15% (or configured floor) at any second, including deadhead | `ev-validator` + mutation/property tests |
| **H2** | Charger Capacity | Concurrent charging sessions at any hub never exceed charger count at any instant; physical plugs never double-booked | `ev-validator` + mutation/property tests |
| **H3** | Trip Timing | Assigned trips start on time and complete before latest allowed finish deadline | `ev-validator` + mutation/property tests |
| **H4** | Assignment Exclusivity | Each trip is assigned to at most one cab; cabs perform at most one activity at a time | `ev-validator` + mutation/property tests |
| **H5** | Reserve to Hub | Cab retains sufficient energy after dropoff to reach a designated charger above the battery floor | `ev-validator` + mutation/property tests |
| **H6** | Charging Profile | Session duration and delivered energy match the two-segment non-linear curve (fast to 80%, slow above) | `ev-validator` + mutation/property tests |

### Numeric Correctness & Integer Math
- **Integer arithmetic exclusively**: Energy in Wh, distance in metres, time in UTC epoch seconds, monetary costs in integer micro-units.
- Floating-point arithmetic (`float`, `double`) is strictly forbidden across `ev-domain`, `ev-planner-core` feasibility checks, and `ev-validator`.
- Conservative rounding: Energy rounds UP, time rounds UP, capacity rounds DOWN.

### Deterministic Charging Curve Model (H6 & Decision D15)

The physical charging profile is governed by a deterministic two-segment piecewise-linear model configured via typed `ChargingCurveConfig(fastThresholdPercent, slowRatePercent)` (defaults: 80% threshold, 50% power ratio):

1. **Fast Segment ($0\% \to 80\%$ SoC):**
   - Operates at 100% of effective plug power: $P_{\text{fast}} = \min(P_{\text{charger}}, P_{\text{vehicle}}) \times 1000\text{ W}$.
   - Duration required to charge $E_{\text{fast}}$ Wh:
     $$\Delta t_{\text{fast}} = \left\lceil \frac{E_{\text{fast}} \times 3600}{P_{\text{fast}}} \right\rceil\text{ seconds}$$
   - Energy delivered in duration $t \le \Delta t_{\text{thresh}}$:
     $$\Delta E_{\text{delivered}} = \left\lfloor \frac{t \times P_{\text{fast}}}{3600} \right\rfloor\text{ Wh}$$

2. **Slow Segment ($80\% \to 100\%$ SoC):**
   - Tapered charging rate at 50% effective plug power: $P_{\text{slow}} = \lfloor P_{\text{fast}} \times 50 / 100 \rfloor\text{ W}$.
   - Duration required to charge $E_{\text{slow}}$ Wh:
     $$\Delta t_{\text{slow}} = \left\lceil \frac{E_{\text{slow}} \times 3600}{P_{\text{slow}}} \right\rceil\text{ seconds}$$
   - Energy delivered in remaining duration $t_{\text{rem}} = t - \Delta t_{\text{thresh}}$:
     $$\Delta E_{\text{delivered}} = \left\lfloor \frac{t_{\text{rem}} \times P_{\text{slow}}}{3600} \right\rfloor\text{ Wh}$$

3. **Conservative Rounding Invariant:**
   - Charging session durations round **UP** to avoid underestimating charger plug occupancy.
   - Energy delivery rounds **DOWN** to avoid overestimating vehicle battery SoC.
   - Energy conservation strictly verified: claimed session target SoC must match curve delivery for the elapsed duration (`RuleId.CLAIMED_SOC_MISMATCH`).

## Verification Gate

The repository enforces a zero-tolerance verification gate via `scripts/verify.sh`:

1. **Spotless**: Google Java Format compliance.
2. **Maven Verify**: Strict compilation (`-Xlint:all -Werror`), Checkstyle, SpotBugs, ArchUnit architectural isolation, and JaCoCo coverage checks.
3. **Forbidden Constructs Grep**: Zero `System.out`/`System.err`, zero `catch (Throwable)` or empty catch blocks, zero `Thread.sleep` in production, zero `float`/`double` in domain/validator/planner, zero `@Disabled` or `@Ignore` tests, zero unchecked non-deterministic clocks.
4. **Log & PII Hygiene**: Grep checks to guarantee zero credentials or PII patterns in code and logs.
5. **Integration Tests**: Testcontainers environment verification.
6. **Release Candidate Status**: Release benchmarking triggers.

### Running Verification Locally

```bash
# Execute the full quality gate
./scripts/verify.sh
```
