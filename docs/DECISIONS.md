# Decisions

Choices that `docs/ARCHITECTURE.md` does not already spell out. Each entry is the rule and the reason it exists.

### 2026-10-30 — Scaffold

The build is a Maven multi-module project on Java 21. Child POMs set `<relativePath>../pom.xml</relativePath>` so an IDE can resolve a submodule on its own. Checkstyle and SpotBugs configs use `${maven.multiModuleProjectDirectory}` so the same files resolve from the root and from a module.

Dependency versions pinned for this tree: Spring Boot 3.5.16, ArchUnit 1.5.1, jqwik 1.10.2, Testcontainers 2.0.5, JaCoCo 0.8.16, SpotBugs 4.10.5.0.

`scripts/verify.sh` treats a grep exit of 2 or more as a failed scan. A missing path is not a clean scan.

Commit `a1c9be4` stays in history. It fails test compilation. Rewriting it is forbidden, and the branch tip already passes the gate.

PIT stays on `STRONGER`. Validator classes are not excluded to chase a mutation score. The gate is JaCoCo plus the named mutation tests.

### D1 — Ground truth

The validator trusts `ValidationInput` for capacities, distances, tariffs, power, and charger counts. It does not trust the same numbers when the candidate plan claims them.

### D2 — Claimed charge

Recomputed watt-hours must match the candidate's claimed state of charge. A mismatch is `CLAIMED_SOC_MISMATCH`.

### D3 — Tenant on every entity

Vehicles, trips, hubs, chargers, and unassigned trips carry a tenant. A mismatch is `TENANT_MISMATCH`.

### D4 — Plugs

A charger must be available, outage windows are half-open, and a plug holds one session.

### D5 — Fail closed

An unexpected exception inside `PlanValidator` becomes an invalid report with `VALIDATOR_INTERNAL_ERROR`. The caller still receives a report.

### D6 — Stable violations

Violations are sorted by rule, vehicle, trip, hub, time, and message, and duplicates are removed. The same plan prints the same report.

### D7 — Inclusive floor

State of charge at the floor is valid. On a 50,001 Wh pack, 7,501 Wh is valid and 7,500 Wh is not.

### D8 — Deadhead factors

Traffic and air-conditioning basis points apply to the empty drive the same way they apply to a loaded trip.

### D9 — Idle

Default idle power is 0 W. A positive value is watts drained continuously during a stationary gap. The energy walk in the validator does not apply it, so waits do not drain charge.

### D10 — Horizon

Every activity and every charging session sits inside `[horizonStart, horizonEnd]`.

### D11 — Rounding order

Base distance energy rounds up to watt-hours. Traffic basis points are applied and the result rounds up. Air-conditioning basis points are applied and the result rounds up again.

### D12 — Grid versus clock

The planner may snap to a 60-second grid, rounding outward. The validator checks the exact epoch seconds.

### D13 — Objective order

Fewer unassigned trips wins. Charging and operating cost is the tie-break.

### D14 — Planning margin

The planner uses a 17 or 18 percent floor. The validator enforces 15 percent.

### D15 — Two-segment curve

Full plug power up to 80 percent of the pack. Half power above that.

### D16 — Strict input, lenient candidate

Ground-truth types reject bad parameters in the constructor. Candidate types (`FleetPlan`, `VehicleSchedule`, `TripAssignment`, `ChargingSession`) stay lenient so the validator can describe a flawed plan instead of throwing.

### D17 — No implicit tenant

`Vehicle`, `Trip`, `Hub`, and `Charger` require a tenant. Source under `src/main` has no sentinel tenant that could skip `TENANT_MISMATCH`.

### D18 — Re-plan knobs

The planning floor is 17 or 18 percent. The lock window is 900 seconds. The change penalty is 1,000,000 micros, so one changed assignment must save more than one currency unit.

### D19 — Deadhead duration is zero

The assignment stores the real deadhead distance and energy, and a duration of 0 seconds. The validator starts a trip at pickup minus the claimed duration, then requires the gap before that start to cover the same drive. A non-zero duration counts the drive twice and can push the activity before the horizon. With duration 0, the gap from the previous drop-off to the pickup is the travel window. Waits do not drain charge, because the validator's energy walk does not apply idle power.

### D20 — When a charge stop is inserted

A charge stop is inserted only after a direct append fails on energy or on the reserve. Its start charge is the cab's charge minus the deadhead to the hub. Its target is the energy the curve delivers in the booked duration, not a requested target the curve might miss. ALNS starts from construction when `maxIterations` is positive, weights the destroy and repair operators, and accepts a worse score when a seeded draw is below a threshold that starts at 2,000/10,000 and is multiplied by 9/10 each iteration. The best score seen is returned. `maxIterations` 0 does not search. A trip that would fit with one more free plug is `CHARGER_CONGESTION`.

### D21 — Flow re-timer

The re-timer runs once after construction and once on the ALNS incumbent. Assignments stay frozen. A session moves only when one contiguous block is feasible and strictly cheaper. The 60-second preemptive flow is a cost lower bound and is not published. Real sessions cannot be split, so the slot flow may spread energy across tariff fragments. That spread is a relaxation. Destroy and repair still insert the earliest feasible stop.

### D22 — Drift ladder

A battery or late-cab drift unlocks only that cab's failing trips inside the lock window. The ladder tries a same-cab charge, then the cab with the most slack, then one and two neighbour trips, then a safe patch if the change count would exceed 3. The 22 percent reading cannot reach the next pickup after a charge, so that trip has to move. Locked trips on other cabs stay. Neighbour repair frees a cab that is busy at the same instant. The safe patch unassigns only the trips that still do not fit. Tier 2 commits only while the plan id is the one the ladder started with.

### D23 — Lower bounds

The backup lower bound is the maximum of intrinsically infeasible trips, peak overlap minus the cab count, and trips that do not fit in the initial battery plus charger throughput. Trip energy for that pack is loaded distance plus a reserve leg. Deadhead is omitted so the bound does not claim backups a real plan can avoid. Overlap treats a drop-off and the next pickup at the same instant as free. The published cost lower bound is the preemptive flow on the plan that was built.

### D24 — Named scenarios

The named shifts are tight-battery, charger-scarce, peak-heavy, and the 2026-11-01 New York fallback, plus the fixed 300-cab evening fleet. The evening fleet ignores the seed. Drift injection sets one constant traffic factor, clamps sensor noise inside the pack, marks one state of charge above capacity as an outlier, and leaves one cab silent. The other profiles stay small and change only by a 0–59 second offset and, for the tight profile, which cab starts low. Readings stay out of the planner input so an impossible charge cannot be stored as a cab's starting energy.

### D25 — Why unassigned

An unassigned trip is offered to every published cab route. If one extra operational plug makes a charge succeed, the reason is charger congestion and the evidence is that hub id plus the session window in UTC. Cab evidence stores arrival charge and the planning floor in watt-hours. The timeline draws the empty drive from the distance provider. The published assignment still has deadhead duration 0 (D19). The extra plug is a probe and is not written onto the plan.

### D26 — Event order

A cancelled trip and a removed cab leave the planner input before the ladder runs. A failed plug is copied as `FAULTY`. A reported charge is accepted only when a session on that plug covers the observation and the reading equals that cab's input charge. A cab strictly below the 15 percent floor is omitted and its trips are re-homed. A batch keeps the latest event for each kind and subject, then applies what remains in time order. At the same timestamp, cab removal and charger failure come before battery drift and trip edits. Events do not carry a time-to-breach, so that kind order is the stand-in. A schedule whose starting charge is below the floor fails the battery check even with no activities. Publishing a different charge would fail the claimed-charge check against the caller's ground truth. The validator omits a trip only when that trip is gone from the input, so a cancellation has to remove it there first.

### D27 — One transaction

Tenant ids stay opaque text. A plan becomes the head only after the validator accepts it. The head compare-and-set, the version, the diff, the snapshot, the read model, the outbox row, the replan run, and the processed event commit together. A full gzip snapshot is stored for version 1, every 50th version, and any version published 10 minutes after the previous snapshot. Writes run as `ev_app`, which does not own the tables, with `app.tenant` set for that transaction. Row-level security compares `tenant_id` to `current_setting('app.tenant')` as text. A replay of the same producer sequence is a no-op. A lost compare-and-set leaves the previous head.

### D28 — Envelope and telemetry

Events use a hand-written protobuf envelope (event id, tenant, producer, sequence, event time, schema version 1). Field numbers live in `ev/events.proto`. Unknown fields are skipped. Generated protobuf classes trip SpotBugs, and the exclude file stays protected, so the codec writes the wire format with protobuf-java and a test locks the field numbers.

The drift gate keeps a reading that jumps faster than 100 Wh/s up or 50 Wh/s down only when it is already under 9,000 Wh. A first reading skips that rate check. A 13,200 Wh reading against a 24,600 Wh plan is a first reading, not a one-second jump. Silence of 120 seconds decays 10 Wh/s. Drift inside 30 seconds is held unless the cab is under that floor.

The consumer does not wait 200 ms to coalesce. Cooldown plus the batch cover a burst, so the poll thread can heartbeat. `max.poll.interval.ms` is 120 seconds, above the 30-second solve, and one record is in flight while that solve runs on a bounded pool. Retries wait 5 seconds, then 1 minute, then a dead-letter topic. A sequence is remembered only after the handler succeeds. A lower sequence is late.

The outbox is read by `ev_relay`, which does not own the tables, and deleted after the send. The datasource user must be a member of `ev_app` and `ev_relay`, and must not be a superuser.

### D29 — HTTP

HTTP reads a validator-clean head and does not publish one. `POST /v1/plans` records an accepted job for the token tenant and shard. Tenant and role come only from the JWT claims `tenant_id` and `role`. `DEVICE` may post battery readings and is not an `app_user` role. `ADMIN` may call the operator endpoints. `Idempotency-Key` is required on mutating posts and replays the stored response. A repeated key on another path is a conflict. The rate limit is one bucket per token subject. Errors are `problem+json` with a correlation id and no exception text. JWKS is configuration. Tests sign RS256 tokens. The stored diff is returned only when `from` is the parent version, or 0 for version 1. Employee trips use the token subject as `employee_ref`. The query names the token tenant as well as the row-level policy, so a tenant query parameter cannot widen the read. A fleet is not assembled from the admin tables, so publication stays on `PlanPublication`.

### D30 — Cache and meters

A published version is keyed by tenant, shard, and version. It does not expire. It is size-bounded. The current head expires after 2 seconds and is dropped when a plan-published event arrives. Fleet rows expire after 60 seconds and are dropped on a config event for that tenant. Base distance is cached for 24 hours by location id. Traffic stays outside the cache. Sizes and lifetimes are `ev.cache`. Config changes are envelope field 14 on `ev.config.v1`.

The web process is the handler for plan events and config, so it does not also run plan projection. The cache key includes the tenant because a shard name is not unique across tenants. Caffeine stays in the service. The distance decorator is not passed into the solver. One handler per topic stays as it was. A missed event is bounded by the pointer lifetime.

Meters record validator rejections, compare-and-set conflicts, replan latency by tier, backups, the charging-cost gap, unassigned trips by reason code, safe patches, duplicate and late drops, retries, and dead letters. Queue depth is the in-memory bus. Consumer lag is one records-lag sum sampled on the poll thread. The lag gauge is a single total because the poller is already at the class-size limit. Logs are ECS JSON and the correlation id is in MDC. Actuator exposes health and prometheus without a token. Alert rules and a dashboard file live under `deploy/observability`. Deadline misses are a counter the worker can raise. This process does not infer them from a publish duration. Percentiles come from the timer. The OpenTelemetry bridge records trace ids and does not export them.

### D31 — Deadline, crash, and the benchmark compile

A drift batch stops between events once the clock is at the deadline and keeps the last plan, marked deadline-exhausted. One hundred readings cannot each take a full ladder inside two seconds. A killed solver does not commit its sequence. The replacement accepts that sequence from the short retry topic. A dropped connection fails before commit, so the head stays, and a cached read does not open the store. Reordered sequences stay on the delivery watermark. Publication opens the database only after the validator accepts the plan.

The JMH annotation processor is not on the verify compile. Generated JMH code calls `Thread.setPriority`, which Error Prone rejects under `-Werror`. The recorded run compiles the benchmark beside the build.

### D32 — Packaging

Two web instances each have their own Kafka consumer group and share one Postgres. A shared group would hide cache events from one instance. Flyway migrates as `ev_admin` before the application connects as `ev_api`. That login is not a superuser and does not own the tables. Keycloak realm `ev` puts `tenant_id` and `role` on the access token. The schema registry receives the envelope proto. Prometheus scrapes both instances and Grafana loads the dashboard. HTTP still does not publish a plan head, and the web process still does not run the ladder, because there is no fleet assembled from the admin tables. Dev passwords match the compose file and are replaced outside this repository. The compose broker uses replication factor 1. Production uses 3 with `min.insync.replicas=2`.

### D33 — Regret table

Regret insertion keeps the feasible cabs for each open trip. A commit that does not book a plug replaces that cab's offer and leaves the others. A commit that books a plug scores every open trip again, because the calendar can block a different cab. The 30 second check is unchanged. Rescoring every cab on every insertion put the 300-cab fleet at 35.8 seconds on the GitHub runner. The selection rule is the same: higher regret, then earlier start, then trip id.

### D34 — Bulk catalog

`POST /v1/admin/hubs`, chargers, vehicles, trips, tariffs, and users accept one object or a JSON array on the same path. `ev.api.max-bulk-items` is 2,000 and `ev.api.max-body-bytes` is 2 MiB. A batch spends one `Idempotency-Key` and one rate-limit token. Each element is `STORED` or `QUARANTINED` with a stable code. The response carries opaque ids and codes, not coordinates or employee refs. A bad element rolls back to its savepoint, so the rest of the batch still commits. `catalog_version` increases once when at least one row is stored, and one `ev.config.v1` event is sent for that batch. Coordinates are written only to `fleet_location`. `FleetSeeder.eveningTrips()` is the 1,501-row body: 1,500 evening trips plus one finish that is before its start.

### D35 — Worker login and required topics

The web instances still connect as `ev_api`. That login can assume `ev_app` and cannot assume `ev_relay` or `ev_worker`. The outbox pump runs only in the worker process, which connects as `ev_worker`. `ev_worker` can assume `ev_app` and `ev_relay`, does not inherit those rights, and does not bypass row-level security. D28's sentence that the datasource user must be a member of both roles now applies to `ev_worker`, not to `ev_api`. If only the web instances are running, outbox rows wait until a worker starts. The worker has no JWT. It copies the tenant from the trigger envelope into `app.tenant`. The web process wrote that envelope from the token. Anyone who can publish to the trigger topic can name a tenant. The broker is not a public API. `detector` is an accepted role name and has no bean yet.

`ev.roles` defaults to `api`. An unknown role stops startup. A second worker uses the compose profile `second-worker` and the group `ev-worker-2`.

The broker does not auto-create topics. `deploy/kafka/create-topics.sh` creates the five base topics and each topic's `.retry.5s`, `.retry.1m`, and `.dlq` before the processes start. Dev uses 6 partitions and replication factor 1. Production is replication factor 3 with `min.insync.replicas=2`. The process calls `TopicLayout.require` before the bus starts and throws if a name is missing. The first missing name on an empty broker is `ev.plan-triggers.v1`.

`SeenSequences` keeps at most `ev.bus` `seenProducers` pairs, default 10,000. Committing a higher sequence moves that pair to the newest slot. Past the cap, the oldest pair is dropped. A later event for a dropped pair is treated as new, so a replay of an evicted producer can be accepted.

### D36 — Job and trigger in one transaction

`POST /v1/plans` inserts `plan_job` and one `outbox` row for `ev.plan-triggers.v1` in the same transaction. The trigger sequence comes from `trigger_seq` for producer `plan-api`. The envelope payload is `PlanRequested` (proto field 15) and carries the shard, the job id, and the mode. A failure between the two statements rolls both back. The outbox `topic` column tells the relay where to send the stored bytes. Plan publications still use `ev.plan-events.v1` and the plan-published marker. Version 0 on a trigger row is not a plan version.

`mode` is `INITIAL` or `FULL_REPLAN`. Omitted, it is `INITIAL` when the shard has no head and `FULL_REPLAN` when it does. `INITIAL` against an existing head is `409 HEAD_EXISTS`. `FULL_REPLAN` with no head is `409 NO_HEAD`. That check is in the same transaction as the insert. Two posts that both see no head can both be accepted. The worker's compare-and-set is what keeps a single head.

Job states are `ACCEPTED`, `RUNNING`, `PUBLISHED`, `REJECTED`, `INFEASIBLE`, `FAILED`, and `STALE_INPUT`. A claim sets `RUNNING` and `lease_until` to now plus `ev.job.lease-seconds` (120). The worker reaper, running as `ev_relay`, sets an expired `RUNNING` job back to `ACCEPTED` and clears the lease. That role can see `RUNNING` and `ACCEPTED` jobs and can only write `ACCEPTED`. It cannot set another status. `GET /v1/plans/jobs/{jobId}` returns the status, the code, and the published version. The version is 0 until a head is stored. The code is empty until a terminal state. Another tenant gets `404 JOB_NOT_FOUND`.

### D37 — The worker solves a plan request

The worker consumes `ev.plan-triggers.v1`. The poll thread pauses the partition and queues the solve. A full solver queue puts the record on the short retry topic and commits the offset, so the record is not dropped. The offset for a solve is committed only after the attempt returns. A handler failure releases the job back to `ACCEPTED`, then the short retry, the long retry, and the dead-letter topic. A dead-lettered `PlanRequested` sets the job to `FAILED` with code `DEAD_LETTER`.

`INITIAL` builds a plan with expected head version 0. `FULL_REPLAN` rebuilds from the current ground truth and publishes only when the head is still the version read before the solve. The validator sees a fleet loaded again at publication, not the planner's own claims. If the catalog version changes during the solve, the worker solves once more. A second change is `STALE_INPUT` with code `CATALOG_MOVED` and publishes nothing. A fleet the loader rejects is `INFEASIBLE` with the loader's code. A lost compare-and-set is `FAILED` with code `CONFLICT`. A validator rejection is `REJECTED` with the rule id.

Publication goes through the `PlanPublication` bean. The web process still does not solve. A battery or charger-failure trigger is applied by D38. Other drift kinds are acknowledged and not applied.

### D38 — Detector, telemetry, and a drifted head

The `detector` role persists telemetry and charger status. A battery reading is stored on `vehicle_state` when its sequence is newer. The gate compares it with the cab's charge on the published snapshot. A drifted reading is a `BATTERY` trigger on `ev.plan-triggers.v1`, using the reading's producer and sequence. A charger-status event stores `OPERATIONAL` or `FAULTY`. A `CHARGER_FAILURE` trigger also records an outage from the observation until the shard horizon when that horizon is still ahead.

The worker runs the ladder for `BATTERY` and `CHARGER_FAILURE`. Other trigger kinds are still acknowledged and not applied. The ladder clock is the trigger's observation time. The candidate is published only when the validator accepts a fleet loaded after the state write. A rejection or a lost compare-and-set leaves the previous head. The stored replan tier and ring are 0 because the ladder does not report which tier won. The change count is the assignment delta. A catalog move during the solve is retried. Silence decay is not on a timer.

### D39 — An unreachable database or broker fails closed

An unreachable database fails readiness. `/actuator/health` and `/actuator/health/readiness` go down, and liveness stays up. Writes and cache-miss reads return 503 `UNAVAILABLE` with `Retry-After`. A cached current head is served until `ev.cache.current-ttl-seconds` (default 2). Connect and socket timeouts are `ev.resilience.connect-timeout-seconds` and `ev.resilience.socket-timeout-seconds` (default 2). After `ev.resilience.failure-limit` failures (default 3) the gate stays open for `ev.resilience.open-millis` (default 5000) and further calls do not wait. One probe is then allowed. A unique-key conflict and a missing row are successful round-trips and do not open the gate. Kafka sends use the same limit on a separate gate. The producer `max.block.ms` and request timeout follow the connect timeout. This replaces the architecture sentence that there is no circuit breaker and no `Retry-After`. `docs/ARCHITECTURE.md` is unchanged.

A paused worker stops heartbeats. `session.timeout.ms` is 6000 and the heartbeat interval is 1000, so the group gives its partitions to the other member. A publish that still expects the old head loses compare-and-set and writes nothing. The worker's own job claim and fleet load use the bounded connect and socket timeouts. They do not share the API call gate.
