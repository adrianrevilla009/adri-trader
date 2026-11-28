-- Everything that touches the trading mode (ADR-0031): challenges, refusals, lockouts and switches. Append-only.
-- It never holds a code, a secret or a token.
CREATE TABLE mode_audit (
    id          BIGSERIAL    PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       TEXT         NOT NULL,
    from_mode   TEXT         NOT NULL CHECK (from_mode IN ('DEMO', 'PROD')),
    to_mode     TEXT         NOT NULL CHECK (to_mode IN ('DEMO', 'PROD')),
    outcome     TEXT         NOT NULL CHECK (outcome IN ('CHALLENGE_ISSUED', 'REFUSED', 'REJECTED', 'LOCKED_OUT', 'SWITCHED')),
    detail      TEXT         NOT NULL
);

CREATE TRIGGER mode_audit_append_only BEFORE UPDATE OR DELETE ON mode_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
