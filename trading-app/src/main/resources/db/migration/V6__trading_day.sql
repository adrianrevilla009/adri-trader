-- One row per agent and day claims the right to trade that day. Claiming is atomic, so two runs of the same day
-- (restart, overlapping jobs) can never both send orders. A row left in STARTED means a run did not finish: it is
-- never retried automatically, because orders may already have gone out (checked by hand).
CREATE TABLE trading_day (
    day         DATE        NOT NULL,
    agent_id    TEXT        NOT NULL,
    status      TEXT        NOT NULL CHECK (status IN ('STARTED', 'DONE', 'FAILED')),
    detail      TEXT,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (day, agent_id)
);
