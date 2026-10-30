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
