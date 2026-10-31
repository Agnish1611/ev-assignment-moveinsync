-- Ground truth the loader reads for one shard. New columns are nullable so catalog
-- rows written before a shard existed still insert. The loader rejects an incomplete fleet.

CREATE TABLE shard (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  shard_id text NOT NULL,
  horizon_start timestamptz NOT NULL,
  horizon_end timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, shard_id),
  CHECK (horizon_start < horizon_end)
);

CREATE TABLE catalog_version (
  tenant_id text PRIMARY KEY REFERENCES tenant (tenant_id),
  version bigint NOT NULL CHECK (version >= 0)
);

CREATE TABLE fleet_location (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  location_id text NOT NULL,
  name text NOT NULL,
  latitude_micros integer,
  longitude_micros integer,
  PRIMARY KEY (tenant_id, location_id)
);

CREATE TABLE vehicle_state (
  tenant_id text NOT NULL,
  vehicle_id text NOT NULL,
  soc_wh bigint NOT NULL CHECK (soc_wh >= 0),
  location_id text NOT NULL,
  latitude_micros integer NOT NULL,
  longitude_micros integer NOT NULL,
  event_time timestamptz NOT NULL,
  seq bigint NOT NULL CHECK (seq >= 0),
  PRIMARY KEY (tenant_id, vehicle_id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle (tenant_id, vehicle_id)
);

CREATE TABLE charger_outage (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  outage_id text NOT NULL,
  charger_id text NOT NULL,
  start_ts timestamptz NOT NULL,
  end_ts timestamptz NOT NULL,
  source_key text NOT NULL,
  PRIMARY KEY (tenant_id, outage_id),
  FOREIGN KEY (tenant_id, charger_id) REFERENCES charger (tenant_id, charger_id),
  CHECK (start_ts < end_ts)
);

ALTER TABLE vehicle
  ADD COLUMN shard_id text,
  ADD COLUMN shift_start_soc_wh bigint,
  ADD COLUMN location_id text,
  ADD COLUMN available_from timestamptz;

ALTER TABLE vehicle
  ADD CONSTRAINT vehicle_shard_fk
  FOREIGN KEY (tenant_id, shard_id) REFERENCES shard (tenant_id, shard_id);

ALTER TABLE trip
  ADD COLUMN shard_id text,
  ADD COLUMN duration_seconds integer;

ALTER TABLE trip
  ADD CONSTRAINT trip_shard_fk
  FOREIGN KEY (tenant_id, shard_id) REFERENCES shard (tenant_id, shard_id);

ALTER TABLE trip
  ADD CONSTRAINT trip_duration_non_negative CHECK (duration_seconds IS NULL OR duration_seconds >= 0);

ALTER TABLE plan_version
  ADD COLUMN catalog_version bigint,
  ADD COLUMN config_hash text;

CREATE INDEX vehicle_shard_idx ON vehicle (tenant_id, shard_id);
CREATE INDEX trip_shard_idx ON trip (tenant_id, shard_id);

ALTER TABLE shard ENABLE ROW LEVEL SECURITY;
ALTER TABLE shard FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shard
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

ALTER TABLE catalog_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON catalog_version
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

ALTER TABLE fleet_location ENABLE ROW LEVEL SECURITY;
ALTER TABLE fleet_location FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fleet_location
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

ALTER TABLE vehicle_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_state
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

ALTER TABLE charger_outage ENABLE ROW LEVEL SECURITY;
ALTER TABLE charger_outage FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON charger_outage
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

GRANT SELECT, INSERT, UPDATE, DELETE
  ON shard, catalog_version, fleet_location, vehicle_state, charger_outage
  TO ev_app;
