-- A plan post stores the job and the trigger together. The relay reads the topic.
-- An expired solve lease is returned to ACCEPTED by ev_relay, which can see every tenant.

ALTER TABLE outbox ADD COLUMN topic text;

UPDATE outbox SET topic = 'ev.plan-events.v1' WHERE topic IS NULL;

ALTER TABLE outbox ALTER COLUMN topic SET NOT NULL;

ALTER TABLE plan_job ADD COLUMN code text;

UPDATE plan_job SET code = '' WHERE code IS NULL;

ALTER TABLE plan_job ALTER COLUMN code SET NOT NULL;

ALTER TABLE plan_job ADD COLUMN published_version bigint;

ALTER TABLE plan_job ADD COLUMN lease_until timestamptz;

ALTER TABLE plan_job ADD COLUMN mode text;

UPDATE plan_job SET mode = 'INITIAL' WHERE mode IS NULL;

ALTER TABLE plan_job ALTER COLUMN mode SET NOT NULL;

ALTER TABLE plan_job
  ADD CONSTRAINT plan_job_status_known CHECK (
    status IN (
      'ACCEPTED',
      'RUNNING',
      'PUBLISHED',
      'REJECTED',
      'INFEASIBLE',
      'FAILED',
      'STALE_INPUT'
    )
  );

ALTER TABLE plan_job
  ADD CONSTRAINT plan_job_mode_known CHECK (mode IN ('INITIAL', 'FULL_REPLAN'));

CREATE TABLE trigger_seq (
  tenant_id text NOT NULL REFERENCES tenant (tenant_id),
  producer text NOT NULL,
  next_seq bigint NOT NULL CHECK (next_seq >= 0),
  PRIMARY KEY (tenant_id, producer)
);

ALTER TABLE trigger_seq ENABLE ROW LEVEL SECURITY;
ALTER TABLE trigger_seq FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON trigger_seq
  USING (tenant_id = current_setting('app.tenant'))
  WITH CHECK (tenant_id = current_setting('app.tenant'));

GRANT SELECT, INSERT, UPDATE, DELETE ON trigger_seq TO ev_app;

GRANT SELECT, UPDATE ON plan_job TO ev_relay;

DROP POLICY tenant_isolation ON plan_job;

CREATE POLICY tenant_isolation ON plan_job
  USING (
    tenant_id = current_setting('app.tenant')
    OR (current_user = 'ev_relay' AND status IN ('RUNNING', 'ACCEPTED'))
  )
  WITH CHECK (
    tenant_id = current_setting('app.tenant')
    OR (current_user = 'ev_relay' AND status = 'ACCEPTED')
  );
