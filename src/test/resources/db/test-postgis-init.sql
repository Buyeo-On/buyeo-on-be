CREATE ROLE buyeoon_application
    LOGIN
    PASSWORD 'application-test-password';

GRANT CONNECT ON DATABASE buyeoon_test TO buyeoon_application;
GRANT USAGE ON SCHEMA public TO buyeoon_application;

ALTER DEFAULT PRIVILEGES FOR ROLE buyeoon_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO buyeoon_application;

ALTER DEFAULT PRIVILEGES FOR ROLE buyeoon_migrator IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO buyeoon_application;
