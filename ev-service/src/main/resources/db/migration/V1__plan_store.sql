-- flyway:executeInTransaction=false
-- CREATE ROLE cannot run inside a Flyway transaction.

CREATE ROLE ev_app NOLOGIN NOINHERIT;

CREATE TABLE tenant (
  tenant_id text PRIMARY KEY
);

CREATE TABLE app_user (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  user_id text NOT NULL,
  role text NOT NULL CHECK (role IN ('EMPLOYEE', 'OPERATOR', 'ADMIN')),
  PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE hub (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  hub_id text NOT NULL,
  name text NOT NULL,
  location_id text NOT NULL,
  zone_id text NOT NULL,
  charger_count integer NOT NULL CHECK (charger_count > 0),
  PRIMARY KEY (tenant_id, hub_id)
);

CREATE TABLE charger (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  charger_id text NOT NULL,
  hub_id text NOT NULL,
  power_kw integer NOT NULL CHECK (power_kw > 0),
  status text NOT NULL,
  PRIMARY KEY (tenant_id, charger_id),
  FOREIGN KEY (tenant_id, hub_id) REFERENCES hub (tenant_id, hub_id)
);

CREATE TABLE tariff_window (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  hub_id text NOT NULL,
  window_id text NOT NULL,
  local_start text NOT NULL,
  local_end text NOT NULL,
  rate_micros bigint NOT NULL CHECK (rate_micros >= 0),
  PRIMARY KEY (tenant_id, window_id),
  FOREIGN KEY (tenant_id, hub_id) REFERENCES hub (tenant_id, hub_id)
);

CREATE TABLE vehicle (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  vehicle_id text NOT NULL,
  battery_wh bigint NOT NULL CHECK (battery_wh > 0),
  consumption_wh_per_km bigint NOT NULL CHECK (consumption_wh_per_km > 0),
  max_charge_kw integer NOT NULL CHECK (max_charge_kw > 0),
  PRIMARY KEY (tenant_id, vehicle_id)
);

CREATE TABLE trip (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  trip_id text NOT NULL,
  start_ts timestamptz NOT NULL,
  from_loc text NOT NULL,
  to_loc text NOT NULL,
  distance_m integer NOT NULL CHECK (distance_m >= 0),
  latest_finish_ts timestamptz NOT NULL,
  employee_ref text NOT NULL,
  PRIMARY KEY (tenant_id, trip_id)
);

CREATE TABLE plan_head (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  shard text NOT NULL,
  version bigint NOT NULL CHECK (version > 0),
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, shard)
);

CREATE TABLE plan_version (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL CHECK (version > 0),
  parent_version bigint,
  input_hash text NOT NULL,
  seed bigint NOT NULL,
  objective_json text NOT NULL,
  bounds_json text NOT NULL,
  validator_report text NOT NULL,
  plan_id text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, shard, version),
  FOREIGN KEY (tenant_id, shard) REFERENCES plan_head (tenant_id, shard)
);

CREATE TABLE plan_diff (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  changes text NOT NULL,
  PRIMARY KEY (tenant_id, shard, version),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE plan_snapshot (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  compressed_plan bytea NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, shard, version),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE route_stop (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  vehicle_id text NOT NULL,
  stop_seq integer NOT NULL CHECK (stop_seq >= 0),
  trip_id text NOT NULL,
  start_ts timestamptz NOT NULL,
  end_ts timestamptz NOT NULL,
  arrival_soc_wh bigint NOT NULL,
  PRIMARY KEY (tenant_id, shard, version, vehicle_id, stop_seq),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE charging_session (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  session_id text NOT NULL,
  vehicle_id text NOT NULL,
  hub_id text NOT NULL,
  charger_id text NOT NULL,
  start_ts timestamptz NOT NULL,
  end_ts timestamptz NOT NULL,
  start_soc_wh bigint NOT NULL,
  target_soc_wh bigint NOT NULL,
  cost_micros bigint NOT NULL,
  PRIMARY KEY (tenant_id, shard, version, session_id),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE unassigned_trip (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  trip_id text NOT NULL,
  reason_code text NOT NULL,
  explanation text NOT NULL,
  PRIMARY KEY (tenant_id, shard, version, trip_id),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE processed_event (
  tenant_id text NOT NULL,
  producer text NOT NULL,
  seq bigint NOT NULL CHECK (seq >= 0),
  shard text NOT NULL,
  version bigint NOT NULL,
  PRIMARY KEY (tenant_id, producer, seq)
);

CREATE TABLE outbox (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  outbox_id bigint GENERATED ALWAYS AS IDENTITY,
  shard text NOT NULL,
  version bigint NOT NULL,
  payload text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, outbox_id)
);

CREATE TABLE replan_run (
  tenant_id text NOT NULL,
  shard text NOT NULL,
  version bigint NOT NULL,
  tier integer NOT NULL CHECK (tier >= 0),
  ring integer NOT NULL CHECK (ring >= 0),
  duration_ms bigint NOT NULL CHECK (duration_ms >= 0),
  changes bigint NOT NULL CHECK (changes >= 0),
  outcome text NOT NULL,
  PRIMARY KEY (tenant_id, shard, version),
  FOREIGN KEY (tenant_id, shard, version) REFERENCES plan_version (tenant_id, shard, version)
);

CREATE TABLE audit_log (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  audit_id bigint GENERATED ALWAYS AS IDENTITY,
  action text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, audit_id)
);

DO $$
DECLARE
  table_name text;
BEGIN
  FOR table_name IN
    SELECT tablename
    FROM pg_tables
    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I USING (tenant_id = current_setting(''app.tenant'')) WITH CHECK (tenant_id = current_setting(''app.tenant''))',
      table_name);
  END LOOP;
END $$;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO ev_app;
REVOKE ALL ON TABLE flyway_schema_history FROM ev_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ev_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ev_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO ev_app;
