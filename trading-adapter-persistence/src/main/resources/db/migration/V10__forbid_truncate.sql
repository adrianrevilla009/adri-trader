-- TRUNCATE does not fire row-level triggers, so the append-only tables could still be emptied in one statement.
-- A statement-level trigger closes that hole for the money ledger and every audit trail.
CREATE TRIGGER fill_no_truncate BEFORE TRUNCATE ON fill
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER cash_ledger_no_truncate BEFORE TRUNCATE ON cash_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trade_order_no_truncate BEFORE TRUNCATE ON trade_order
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER agent_config_audit_no_truncate BEFORE TRUNCATE ON agent_config_audit
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER mode_audit_no_truncate BEFORE TRUNCATE ON mode_audit
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
