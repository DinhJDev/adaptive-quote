CREATE TABLE quotes (
    id uuid PRIMARY KEY,
    owner_digest varchar(64) NOT NULL,
    request_digest varchar(64) NOT NULL,
    catalog_version varchar(40) NOT NULL,
    record_version smallint NOT NULL CHECK (record_version = 1),
    selection jsonb NOT NULL CHECK (jsonb_typeof(selection) = 'object'),
    line_items jsonb NOT NULL CHECK (jsonb_typeof(line_items) = 'array'),
    total_minor integer NOT NULL CHECK (total_minor >= 0),
    currency varchar(3) NOT NULL CHECK (currency = 'USD'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at)
);

CREATE INDEX quotes_expiry ON quotes (expires_at);
