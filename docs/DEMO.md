# Demo

Dev-only passwords. They are the ones in `docker-compose.yml` and `deploy/keycloak/ev-realm.json`.

## Start

```bash
docker compose up --build
```

Wait until both application health checks pass. Grafana is [http://localhost:3001/d/ev-fleet/ev-fleet](http://localhost:3001/d/ev-fleet/ev-fleet). Prometheus targets are [http://localhost:9090/targets](http://localhost:9090/targets).

## Health on both instances

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8081/actuator/health
```

Both return `{"status":"UP"}`.

## Token

```bash
curl -sS -X POST http://localhost:8180/realms/ev/protocol/openid-connect/token \
  -d 'grant_type=password' \
  -d 'client_id=ev-api' \
  -d 'username=admin' \
  -d 'password=admin'
```

Use `access_token` as `$TOKEN` below. `operator` / `operator` can post battery and jobs. `admin` / `admin` can also write the catalog. `employee` / `employee` is limited to its own trips. `device` / `device` can post battery only.

## Catalog write, then the same key again

```bash
curl -sS -D - -X POST http://localhost:8080/v1/admin/hubs \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: hub-demo-1' \
  -d '{"hubId":"HUB-1","name":"Central","locationId":"LOC-1","zone":"Asia/Kolkata","chargerCount":4}'
```

The first response is `201` and `{"id":"HUB-1"}`. Repeat the same command. The stored response is replayed. A different path with `hub-demo-1` is `409`.

## A job does not publish a head

```bash
curl -sS -D - -X POST http://localhost:8080/v1/plans \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: job-demo-1' \
  -d '{"shard":"shard-demo"}'

curl -sS -D - "http://localhost:8080/v1/plans/current?shard=shard-demo" \
  -H "Authorization: Bearer $TOKEN"
```

The post is `202` with `"status":"ACCEPTED"`. The read is `404` and `PLAN_NOT_FOUND`. Nothing was published.

## Battery event

```bash
curl -sS -D - -X POST http://localhost:8080/v1/events/battery \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: battery-demo-1' \
  -d '{"cabId":"CAB-1","socWh":24600,"observedAt":"2026-10-30T09:00:00Z","sequence":1}'
```

The response is `202` and `{"status":"ACCEPTED"}`. The envelope is on Kafka. These two processes do not solve it. The solve path is the worker covered by `DriftStormTest` and `WorkerCrashTest`.

## Live failure: stop one instance

```bash
docker stop ev-app-1
curl -sS http://localhost:8081/actuator/health
curl -sS -D - -X POST http://localhost:8081/v1/admin/vehicles \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: vehicle-demo-1' \
  -d '{"vehicleId":"CAB-1","batteryWh":60000,"consumptionWhPerKm":150,"maxChargeKw":50}'
docker start ev-app-1
```

`app-2` stays up and accepts the vehicle. `app-1` is the instance that stopped. Start it again when the demo is done.

The solver failure is not this stop. `WorkerCrashTest` kills a solve before the sequence is committed. The replacement finishes that sequence, and the head moves once.
