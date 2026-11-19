-- Raw data exactly as received per source; never modified.
CREATE TABLE bar_daily_raw (
    instrument_id BIGINT        NOT NULL REFERENCES instrument(id),
    bar_date      DATE          NOT NULL,
    source        VARCHAR(32)   NOT NULL,
    open          NUMERIC(18,6) NOT NULL,
    high          NUMERIC(18,6) NOT NULL,
    low           NUMERIC(18,6) NOT NULL,
    close         NUMERIC(18,6) NOT NULL,
    volume        BIGINT        NOT NULL,
    available_on  DATE          NOT NULL,
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, bar_date, source)
);

-- Clean series used by the backtest; regenerated from raw.
CREATE TABLE bar_daily (
    instrument_id BIGINT        NOT NULL REFERENCES instrument(id),
    bar_date      DATE          NOT NULL,
    open          NUMERIC(18,6) NOT NULL,
    high          NUMERIC(18,6) NOT NULL,
    low           NUMERIC(18,6) NOT NULL,
    close         NUMERIC(18,6) NOT NULL,
    volume        BIGINT        NOT NULL,
    available_on  DATE          NOT NULL,
    PRIMARY KEY (instrument_id, bar_date)
);

CREATE TABLE corporate_action (
    instrument_id BIGINT      NOT NULL REFERENCES instrument(id),
    action_date   DATE        NOT NULL,
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('DIVIDEND', 'SPLIT', 'TICKER_CHANGE')),
    detail        VARCHAR(200),
    PRIMARY KEY (instrument_id, action_date, kind)
);

CREATE TABLE data_quality_issue (
    id            BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT      NOT NULL REFERENCES instrument(id),
    issue_date    DATE        NOT NULL,
    type          VARCHAR(24) NOT NULL,
    severity      VARCHAR(8)  NOT NULL CHECK (severity IN ('WARNING', 'CRITICAL')),
    detail        VARCHAR(300),
    status        VARCHAR(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (instrument_id, issue_date, type)
);
