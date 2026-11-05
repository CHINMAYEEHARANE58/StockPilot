CREATE TABLE login_attempts (email varchar(255) PRIMARY KEY, failure_count integer NOT NULL DEFAULT 0, locked_until timestamptz, updated_at timestamptz NOT NULL DEFAULT now());
