CREATE TABLE ai_budget (
    month_start date PRIMARY KEY,
    accounted_micros integer NOT NULL CHECK (accounted_micros BETWEEN 0 AND 4000000),
    last_started_at timestamptz NOT NULL
);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'quote_app') THEN
        GRANT SELECT, INSERT, UPDATE ON ai_budget TO quote_app;
    END IF;
END
$$;
