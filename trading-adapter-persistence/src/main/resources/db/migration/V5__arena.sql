-- Daily journal of the arena. State can be rebuilt by replaying data; this is the audit trail and what reports read.
CREATE TABLE arena_agent_day (
    day         DATE           NOT NULL,
    agent_id    TEXT           NOT NULL,
    status      TEXT           NOT NULL,
    equity      NUMERIC(18, 4) NOT NULL,
    orders_sent INTEGER        NOT NULL,
    error       TEXT,
    recorded_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (day, agent_id)
);
CREATE INDEX arena_agent_day_agent ON arena_agent_day (agent_id, day);
