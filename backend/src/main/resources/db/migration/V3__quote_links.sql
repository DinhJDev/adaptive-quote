CREATE TABLE quote_links (
    id uuid PRIMARY KEY,
    record_version smallint NOT NULL CHECK (record_version = 1),
    snapshot jsonb NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '7 days')
);

CREATE INDEX quote_links_expiry ON quote_links (expires_at);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'quote_app') THEN
        GRANT SELECT, INSERT ON quote_links TO quote_app;
    END IF;
END
$$;
