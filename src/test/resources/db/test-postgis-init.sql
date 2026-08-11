CREATE EXTENSION IF NOT EXISTS postgis;

CREATE ROLE buyeoon_app
    LOGIN
    PASSWORD 'application-test-password';

GRANT CONNECT ON DATABASE buyeoon_test TO buyeoon_app;
GRANT USAGE, CREATE ON SCHEMA public TO buyeoon_app;
