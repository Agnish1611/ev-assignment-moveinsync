-- flyway:executeInTransaction=false
-- CREATE ROLE cannot run inside a Flyway transaction.
-- Dev-only login. The password matches docker-compose.yml. Production replaces this role.
-- ev_worker does not bypass row-level security and does not inherit table rights.

CREATE ROLE ev_worker LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD 'ev_password';

GRANT ev_app TO ev_worker;

GRANT ev_relay TO ev_worker;

REVOKE ev_relay FROM ev_api;
