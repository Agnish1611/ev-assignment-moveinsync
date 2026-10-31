# Architecture

This service assigns a shift of trips to an electric fleet, inserts charging stops, and publishes a plan only after an independent validator accepts it. A trip the fleet cannot cover goes to a backup cab. The battery floor, charger capacity, trip timing, and the reserve to reach a hub stay in force when the solver runs out of time.

The stack is Java 21, Spring Boot, PostgreSQL, and Kafka. The planner and the validator are plain Java. They share domain types and nothing else.

## Modules

| Module | Responsibility |
|---|---|
| `ev-domain` | Immutable values. Energy is watt-hours, distance is metres, time is UTC epoch seconds, money is integer micro-units. |
| `ev-planner-core` | Construction, ALNS, the flow re-timer, lower bounds, and the reason a trip was left unassigned. No Spring and no I/O. |
| `ev-validator` | Checks a candidate against ground truth. It depends only on `ev-domain`. |
| `ev-service` | HTTP, Keycloak, PostgreSQL, and Kafka. |
| `ev-sim` | The 300-cab fleet, smaller scale points, and drift injection. |
| `ev-bench` | The JMH charge-duration benchmark and the k6 health script. |

`ValidatorArchitectureTest` fails the build if the validator imports the planner or the service. The planner does not import the validator. Publication in `ev-service` calls `PlanValidator` and stores a head only when the report is valid.

## Invariants

Coverage is soft. Safety is not. On a timeout, a bad reading, or a rejected candidate, affected trips go to backup and the previous valid head stays.

| Rule | Requirement |
|---|---|
| H1 | State of charge stays at or above 15 percent of pack capacity, including the empty drive to a pickup. The floor is inclusive: one watt-hour under it is a violation. |
| H2 | Concurrent sessions at a hub never exceed its charger count. A plug holds one session. Intervals are half-open. |
| H3 | An assigned trip starts on time and finishes by its latest allowed finish. |
| H4 | A trip belongs to one cab. A cab does one thing at a time. |
| H5 | After every trip the cab can still reach a charger with charge left above the floor. |
| H6 | A session lasts at least as long as the two-segment curve needs for the energy it claims. |

The baseline planning margin is 18 percent. 17 percent is also allowed. The validator enforces the real 15 percent, so a published plan is never under the floor the checker uses.

## Units and the charging curve

Constraint code uses integers. Energy rounds up on the road and down while charging. Time rounds up. Capacity rounds down.

Plug power is the minimum of the charger and the vehicle's maximum acceptance, in watts. Up to 80 percent of the pack the session uses that power. Above 80 percent it uses half, integer division. Duration for a block of energy is `ceil(watt-hours * 3600 / watts)`. Energy delivered in a duration is `floor(seconds * watts / 3600)`. A claimed target that does not match that delivery is `CLAIMED_SOC_MISMATCH`.

Consumption is base watt-hours, rounded up, then a traffic factor in basis points, rounded up, then an air-conditioning factor in basis points, rounded up. Ten thousand basis points is 1.0. The same factors apply to the empty drive. Idle waits do not drain the pack. The validator's energy walk has no idle power.

Tariffs are stored in the hub's local zone and converted to UTC. Tests cover a daylight-saving transition and a shift that crosses midnight.

## Planner

A cab route is an ordered list of trips and charge stops. The empty drive is the gap from the previous drop-off to the next pickup. Its distance and energy are stored. Its duration on the assignment is 0, so the drive is not counted twice against the horizon.

The first plan minimises backup trips, then charging cost. A re-plan minimises backup trips, then `1_000_000 * changed assignments + charging cost`. One changed assignment must save more than one currency unit before the re-plan prefers it. The change budget is 3. Trips inside the 900-second lock window stay put unless that cab is below the floor.

The pipeline is:

1. Regret-2 construction. Trips that fail on energy or on the reserve get one charge stop when a plug is free before the pickup. Anything still open at the deadline goes to backup.
2. ALNS, when `maxIterations` is positive. Removal is random, worst-cost, or Shaw. Repair is greedy or regret. A worse score is accepted when a seeded draw falls under a threshold that starts at 2,000 / 10,000 and is multiplied by 9/10 each iteration. The best score seen is returned. `maxIterations` 0 does not search. The 300-cab benchmark uses 0.
3. A flow re-timer with assignments frozen. The 60-second preemptive flow is a cost lower bound and is not published. A published session is one contiguous block, and it moves only when that block is feasible and strictly cheaper.
4. Validation, then compare-and-set of the plan head.

Construction is O(T² · C · H · P) time when every accepted trip books a plug, and O(T · C) space for the regret table. A commit that does not book a plug re-probes only that cab. T is trips, C is cabs, H is hubs, and P is plugs. ALNS is O(I · T² · C · H) for I iterations. The backup lower bound is O(T · C · H + T log T) time and O(T) space. The seed and the input hash are stored on every version, so a run can be repeated.

The backup lower bound is the maximum of three counts: trips no cab can cover even with a full pack, peak overlap beyond the cab count, and trips that do not fit in the starting batteries plus charger throughput. Trip energy in that pack is the loaded distance plus a reserve leg. Deadhead is omitted, so the bound stays low. The cost lower bound is the preemptive flow on the plan that was built. The 300-cab evening fleet is feasible, so both the bound and the result are 0 backups and 0 charging cost. A tighter demand pattern can leave backups. The case study's illustration of 1,462 covered trips and 38 backups is that kind of pattern. This repository does not invent that result for the generated fleet.

An unassigned trip is offered to every published route. If one extra plug makes the charge succeed, the reason is charger congestion and the evidence is the hub id plus the session window in UTC. Cab evidence stores arrival watt-hours and the planning floor. The synthetic plug is not written onto the plan.

On this machine one construction of 300 cabs, 40 chargers, and 1,500 trips took 2.710 seconds. That is the largest fleet measured here. Smaller points are in `docs/reports/PROOF_LATENCY.md`.

## Re-plan ladder

Triggers are a battery reading, a failed plug, a late cab, a new trip, a cancellation, or a removed cab. A batch keeps the latest event of each kind and subject, then applies the rest in time order. At the same timestamp, cab removal and charger failure come before battery drift and trip edits. The consumer does not wait to coalesce. A burst is covered by the cooldown and by this batch.

The ladder polls a deadline and stops with the last validator-clean plan, marked deadline-exhausted.

1. Tier 0. Same cab, insert a charge, or move the trip to the cab with the most slack.
2. Tier 1. One neighbour trip, then two, inside the change budget.
3. Tier 2. Background polish. It is published only when the objective improves, the change budget holds, and the plan id is still the one the ladder started with.
4. Safe patch. Unassign only the trips that still do not fit, and send the cab to charge.

A reading of 22 percent on a cab the plan expected at 41 percent cannot reach the next pickup after a charge, so that trip moves. At most two other assignments change. Locked trips on other cabs stay. A cab already under 15 percent is omitted and its trips are re-homed. A schedule that starts under the floor fails the battery check even with no activities.

Telemetry is filtered before it becomes a trigger. A jump faster than 100 Wh/s up or 50 Wh/s down is kept only when the cab is already under 9,000 Wh. The first reading skips that check. Silence of 120 seconds decays the estimate by 10 Wh/s. Drift inside 30 seconds is held unless the cab is under the floor. A reported state of charge is written onto the plan only when it matches the cab's input energy and a session on that plug covers the observation.

## Events and publication

The envelope is hand-written Protobuf: event id, tenant, producer, sequence, event time, schema version 1. Field numbers live in `ev/events.proto`. Unknown fields are skipped. The schema registry subject is `ev.envelope.v1`.

| Topic | Key | Use |
|---|---|---|
| `ev.telemetry.v1` | tenant and cab | Battery readings |
| `ev.charger-status.v1` | tenant and hub | Plug up or down |
| `ev.plan-triggers.v1` | tenant and shift | What the worker reads |
| `ev.plan-events.v1` | tenant and shift | A published version and its diff |
| retry, then dead letter | same key | 5 seconds, then 1 minute, then the dead-letter topic |

Processing is at-least-once and idempotent. The processed-event row is inserted in the same transaction as the plan head, the version, the diff, the snapshot, the read model, the outbox row, and the replan run. A replay of the same `(tenant, producer, seq)` is a no-op. A lower sequence is late. The sequence is stored only after the handler returns, so a killed solve is retried rather than committed.

One writer owns a shard. A second writer loses the compare-and-set and discards its result. The solver runs on a bounded pool. `max.poll.interval.ms` is 120 seconds, above the 30-second solve, and one record is in flight during that solve.

Snapshots are gzip. Version 1 is stored in full, then every 50th version, and any version published 10 minutes after the previous snapshot.

The two web processes each use their own consumer group, `ev-web-1` and `ev-web-2`, so a plan-published event reaches both caches. They do not run the ladder. `POST /v1/plans` records an accepted job and does not publish a head. Compose runs Kafka with replication factor 1. Production is replication factor 3 with `min.insync.replicas=2`.

## Data and tenancy

Tenant ids are opaque text. Row-level security compares `tenant_id` to `current_setting('app.tenant')`. There is no cast to uuid. Policies are forced. A superuser would bypass them, so the application role is not the table owner.

`ev_admin` owns the tables and runs Flyway, including the `ev_api` login. The application connects as `ev_api`, which is not a superuser and does not inherit table rights. A request does `SET LOCAL ROLE ev_app` and sets `app.tenant` from the token. The outbox pump uses `ev_relay` and deletes a row after the send.

Tables: `tenant`, `app_user`, `hub`, `charger`, `tariff_window`, `vehicle`, `trip`, `plan_head`, `plan_version`, `plan_diff`, `plan_snapshot`, `route_stop`, `charging_session`, `unassigned_trip`, `processed_event`, `outbox`, `replan_run`, `audit_log`, `plan_job`, `idempotent_call`. `employee_ref` is an opaque id. Logs and error bodies do not carry phone numbers, email addresses, or coordinates.

## HTTP

Keycloak realm `ev` puts `tenant_id` and `role` on the access token. The resource server checks the signature against the JWKS and does not read the tenant from the body or the query. Roles are `EMPLOYEE`, `OPERATOR`, `ADMIN`, and `DEVICE`. Employees read `GET /v1/me/trips` for the token subject. Devices may post battery only. Admins may call the operator endpoints.

Mutating posts require `Idempotency-Key`, charset `[A-Za-z0-9_-]{1,64}`. The same key and path replay the stored response. The same key on another path is `409`. The rate limit is one bucket per token subject.

Errors are `problem+json` with `type`, `title`, `status`, `code`, and `correlationId`. There is no stack trace and no exception text.

| Method and path | Who |
|---|---|
| `POST /v1/plans` | operator, admin. Returns 202 and a job. Does not publish. |
| `GET /v1/plans/current`, `GET /v1/plans/{v}`, diff, validation report, unassigned | operator, admin |
| `GET /v1/cabs/{id}/timeline` | operator, admin |
| `POST /v1/events/battery` | operator, device |
| `POST /v1/chargers/{id}/fail`, `POST /v1/replans` | operator, admin |
| `GET /v1/me/trips` | employee |
| `POST /v1/admin/hubs`, chargers, vehicles, trips, tariffs, users | admin |

`/actuator/health` and `/actuator/prometheus` are open so Prometheus can scrape them. Health hides details. Every other path is denied.

## Cache

There is no Redis. Caffeine is inside the web process. The planner does not take a cache, and the distance cache is not passed into the solver.

| Data | Key and lifetime | Invalidation |
|---|---|---|
| Published version | tenant, shard, version. No expiry. Size-bounded. | Immutable |
| Current head | expires after 2 seconds | dropped on `plan-published` |
| Fleet rows | 60 seconds | dropped on a config event for that tenant |
| Base distance | 24 hours, by location id | traffic is not cached |

A missed event is bounded by those lifetimes. Config changes are envelope field 14 on `ev.config.v1`.

## Observability

Meters cover validator rejections, compare-and-set conflicts, re-plan latency by tier, backups, the charging-cost gap, unassigned trips by reason, safe patches, duplicate and late drops, retries, and dead letters. Queue depth is the in-memory bus. Consumer lag is one records-lag sum sampled on the poll thread. Logs are ECS JSON with the correlation id in MDC. Trace ids stay in the process. There is no collector.

Prometheus scrapes both instances. Grafana loads the dashboard `ev-fleet`. Alert rules live under `deploy/observability`.

## When something fails

| What fails | What remains | Recovery |
|---|---|---|
| Solver killed mid-solve | The previous head | The sequence was not stored. The short retry delivers it to the replacement. |
| Deadline during a batch | The last valid plan | The batch stops. A later event can repair again. |
| Validator rejects the candidate | The previous head | The seed on a stored version repeats a past run. |
| Duplicate or older message | No second write | The delivery watermark keeps the highest sequence. |
| Poison payload | No write | Retry topics, then the dead-letter topic. |
| Database connection drops during publish | The last committed head | The body is `STORE_FAILED`. It contains no SQL. |
| Database down on a cached read | The cached version, inside its lifetime | The head pointer expires after 2 seconds. |
| One web instance stops | The other instance | Both serve health, reads, and catalog writes. |
| Kafka down | A stored head is still readable | A trigger post fails. Outbox rows wait for the relay. |
| Second writer on the same shard | The first head | Compare-and-set returns no row. |
| Cab silent | A decayed estimate | The ladder runs if the decay projects a breach. |
| Cross-tenant read | No row | The token tenant is set again in the database session. The response is 404. |

The web process does not assemble a fleet from the admin tables, so it does not start the ladder. There is no circuit breaker and no `Retry-After`. There is no Kubernetes manifest, no second region, no Debezium connector, and no learned consumption model. The outbox pump publishes rows a worker has already committed. `docs/DESIGN_NOTE.md` is the short form of this, including the battery-drift sequence.
