CREATE TABLE instrument (
    id             BIGSERIAL PRIMARY KEY,
    isin           CHAR(12)     NOT NULL,
    exchange       VARCHAR(16)  NOT NULL,
    currency       CHAR(3)      NOT NULL,
    etoro_id       BIGINT,
    distribution   VARCHAR(4)   NOT NULL CHECK (distribution IN ('ACC', 'DIST')),
    name           VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (isin, exchange)
);
