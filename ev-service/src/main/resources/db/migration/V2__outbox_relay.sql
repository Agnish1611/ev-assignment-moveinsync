-- flyway:executeInTransaction=false
-- CREATE ROLE cannot run inside a Flyway transaction.

CREATE ROLE ev_relay NOLOGIN NOINHERIT;

GRANT SELECT, UPDATE, DELETE ON TABLE outbox TO ev_relay;

DROP POLICY tenant_isolation ON outbox;

CREATE POLICY tenant_isolation ON outbox
  USING (tenant_id = current_setting('app.tenant', true))
  WITH CHECK (tenant_id = current_setting('app.tenant', true));

CREATE POLICY outbox_relay_read ON outbox
  FOR SELECT
  USING (current_user = 'ev_relay');

CREATE POLICY outbox_relay_change ON outbox
  FOR UPDATE
  USING (current_user = 'ev_relay')
  WITH CHECK (current_user = 'ev_relay');

CREATE POLICY outbox_relay_delete ON outbox
  FOR DELETE
  USING (current_user = 'ev_relay');
