-- Ledger (TRD section 5). Append-only: corrections are compensating entries.
-- trade_order instead of "order" because ORDER is a reserved word in SQL.
CREATE TABLE trade_order (
    id              UUID PRIMARY KEY,
    agent_id        VARCHAR(64)   NOT NULL,
    instrument_id   BIGINT        NOT NULL REFERENCES instrument(id),
    side            VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),
    amount_eur      NUMERIC(18,6) NOT NULL CHECK (amount_eur > 0),
    limit_price     NUMERIC(18,6),
    state           VARCHAR(20)   NOT NULL
                    CHECK (state IN ('CREATED','SUBMITTED','PARTIALLY_FILLED','FILLED','CANCELLED','REJECTED')),
    broker_order_id VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE fill (
    id          UUID PRIMARY KEY,
    order_id    UUID           NOT NULL REFERENCES trade_order(id),
    quantity    NUMERIC(24,10) NOT NULL CHECK (quantity > 0),
    price       NUMERIC(18,6)  NOT NULL CHECK (price > 0),
    fee_eur     NUMERIC(18,6)  NOT NULL DEFAULT 0,
    filled_at   TIMESTAMPTZ    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE cash_ledger (
    id          BIGSERIAL PRIMARY KEY,
    agent_id    VARCHAR(64)   NOT NULL,
    kind        VARCHAR(16)   NOT NULL CHECK (kind IN ('DEPOSIT','WITHDRAWAL','FEE','DIVIDEND','TRADE','ADJUSTMENT')),
    amount_eur  NUMERIC(18,6) NOT NULL,
    ref_id      UUID,
    occurred_at TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER fill_append_only BEFORE UPDATE OR DELETE ON fill
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER cash_ledger_append_only BEFORE UPDATE OR DELETE ON cash_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
-- trade_order.state is updated by the state machine, but rows are never deleted.
CREATE TRIGGER trade_order_no_delete BEFORE DELETE ON trade_order
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
