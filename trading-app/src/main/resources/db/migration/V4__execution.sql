-- Execution support: note per order, EUR amount per fill, and the kill switch flag.
ALTER TABLE trade_order ADD COLUMN note VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE fill ADD COLUMN amount_eur NUMERIC(18,6) NOT NULL DEFAULT 0;

-- Single row. Active = nothing new is sent; it never liquidates positions.
CREATE TABLE kill_switch (
    id         INT PRIMARY KEY CHECK (id = 1),
    active     BOOLEAN      NOT NULL,
    reason     VARCHAR(300),
    changed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO kill_switch(id, active, reason) VALUES (1, FALSE, NULL);
