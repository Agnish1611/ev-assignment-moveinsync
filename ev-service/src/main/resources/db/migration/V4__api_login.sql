-- flyway:executeInTransaction=false
-- CREATE ROLE cannot run inside a Flyway transaction.
-- Dev-only login. The password matches docker-compose.yml. Production replaces this role.

CREATE ROLE ev_api LOGIN NOINHERIT PASSWORD 'ev_password';

GRANT ev_app TO ev_api;

GRANT ev_relay TO ev_api;
