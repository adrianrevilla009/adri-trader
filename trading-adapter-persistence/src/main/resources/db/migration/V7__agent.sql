-- Agents configured from the API/UI (ADR-0030). Params and escalation steps are plain tables, so no JSON is needed.
-- agent_config_audit is append-only: every change records who, when, the old value and the new one.
CREATE TABLE agent (
    id          VARCHAR(64)   PRIMARY KEY,
    strategy_id TEXT          NOT NULL,
    capital     NUMERIC(18,6) NOT NULL CHECK (capital > 0),
    mode        TEXT          NOT NULL CHECK (mode IN ('DEMO', 'PROD')),
    state       TEXT          NOT NULL CHECK (state IN ('ACTIVE', 'STOPPED')),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE agent_param (
    agent_id VARCHAR(64)    NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    name     TEXT           NOT NULL,
    value    NUMERIC(24,10) NOT NULL,
    PRIMARY KEY (agent_id, name)
);

CREATE TABLE agent_step (
    agent_id   VARCHAR(64)   NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    position   INT           NOT NULL,
    after_days INT           NOT NULL CHECK (after_days > 0),
    capital    NUMERIC(18,6) NOT NULL CHECK (capital > 0),
    PRIMARY KEY (agent_id, position)
);

CREATE TABLE agent_config_audit (
    id         BIGSERIAL    PRIMARY KEY,
    agent_id   VARCHAR(64)  NOT NULL,
    changed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by TEXT         NOT NULL,
    field      TEXT         NOT NULL,
    old_value  TEXT,
    new_value  TEXT
);

CREATE TRIGGER agent_config_audit_append_only BEFORE UPDATE OR DELETE ON agent_config_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
