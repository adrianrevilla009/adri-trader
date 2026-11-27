-- Instruments each agent may trade (ADR-0030). The order matters: buy-and-hold uses the first one.
-- Plain ISIN text, not a foreign key: an agent can be defined before its instrument is ingested; the application checks it.
CREATE TABLE agent_universe (
    agent_id VARCHAR(64) NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    position INT         NOT NULL,
    isin     CHAR(12)    NOT NULL,
    PRIMARY KEY (agent_id, position),
    UNIQUE (agent_id, isin)
);
