-- Runs once, on first container start (docker-entrypoint-initdb.d), against the default
-- "postgres" database. Each service gets its own database on this single Postgres instance
-- (database-per-service pattern), matching the local dev setup.
CREATE DATABASE userdb;
CREATE DATABASE catalogdb;
CREATE DATABASE orderdb;
CREATE DATABASE notificationdb;
