CREATE TABLE plan_job (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  job_id text NOT NULL,
  shard text NOT NULL,
  status text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, job_id)
);

CREATE TABLE idempotent_call (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  user_id text NOT NULL,
  idempotency_key text NOT NULL,
  method text NOT NULL,
  path text NOT NULL,
  status_code integer NOT NULL,
  response_body text NOT NULL,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, user_id, idempotency_key)
);

ALTER TABLE plan_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan_job
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

ALTER TABLE idempotent_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotent_call FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON idempotent_call
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

GRANT SELECT, INSERT, UPDATE, DELETE ON plan_job, idempotent_call TO ev_app;
