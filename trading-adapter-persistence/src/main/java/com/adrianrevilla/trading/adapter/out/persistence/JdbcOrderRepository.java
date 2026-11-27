package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.execution.domain.OrderStateMachine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Orders and fills in PostgreSQL. Fills are append-only (database trigger). */
public class JdbcOrderRepository implements OrderRepository {
    private static final String SELECT = """
            SELECT o.id, o.agent_id, i.isin, o.side, o.amount_eur, o.limit_price, o.state, o.note
            FROM trade_order o JOIN instrument i ON i.id = o.instrument_id""";
    private static final String SELECT_BY_ID = SELECT + " WHERE o.id = ?";
    private static final String SELECT_OPEN =
            SELECT + " WHERE o.state IN ('SUBMITTED','PARTIALLY_FILLED') ORDER BY o.created_at";

    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public JdbcOrderRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    @Override
    public void create(OrderRequest r) {
        try {
            int n = jdbc.update("""
                    INSERT INTO trade_order(id, agent_id, instrument_id, side, amount_eur, limit_price, state, note)
                    SELECT ?, ?, i.id, ?, ?, ?, 'CREATED', '' FROM instrument i WHERE i.isin = ? LIMIT 1""",
                    r.internalId(), r.agentId(), r.side().name(), r.amountEur(), r.limitPrice(), r.isin().value());
            if (n == 0) {
                throw new IllegalArgumentException("Instrument not in database: " + r.isin().value());
            }
        } catch (DuplicateKeyException e) {
            throw new DuplicateOrderException(r.internalId());
        }
    }

    @Override
    public void updateState(UUID id, OrderState state, String note) {
        OrderRecord current = find(id).orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
        OrderStateMachine.require(current.state(), state);
        // compare-and-set: if another thread moved the order meanwhile, nothing is overwritten
        int n = jdbc.update("UPDATE trade_order SET state = ?, note = ? WHERE id = ? AND state = ?", state.name(), note, id,
                current.state().name());
        if (n == 0) {
            throw new IllegalStateException("Order " + id + " changed state concurrently; not overwritten");
        }
    }

    @Override
    public Optional<OrderRecord> find(UUID id) {
        return jdbc.query(SELECT_BY_ID, (rs, i) -> record(rs), id).stream().findFirst();
    }

    @Override
    public List<OrderRecord> findOpen() {
        return jdbc.query(SELECT_OPEN,
                (rs, i) -> record(rs));
    }

    @Override
    public BigDecimal recordedFillAmount(UUID id) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(SUM(amount_eur), 0) FROM fill WHERE order_id = ?",
                BigDecimal.class, id);
        return v == null ? BigDecimal.ZERO : v;
    }

    @Override
    public void recordFill(UUID orderId, BigDecimal quantity, BigDecimal price, BigDecimal amountEur) {
        jdbc.update("INSERT INTO fill(id, order_id, quantity, price, amount_eur, filled_at) VALUES (?,?,?,?,?, now())",
                UUID.randomUUID(), orderId, quantity, price, amountEur);
    }

    @Override
    public boolean recordFillUpTo(UUID orderId, BigDecimal brokerFilledTotalEur, BigDecimal averagePrice) {
        Boolean recorded = tx.execute(status -> {
            // the row lock serializes every writer of this order, so the delta below cannot be counted twice
            jdbc.queryForList("SELECT id FROM trade_order WHERE id = ? FOR UPDATE", UUID.class, orderId);
            BigDecimal delta = brokerFilledTotalEur.subtract(recordedFillAmount(orderId));
            if (delta.signum() <= 0) {
                return false;
            }
            BigDecimal qty = averagePrice != null && averagePrice.signum() > 0
                    ? delta.divide(averagePrice, java.math.MathContext.DECIMAL128) : BigDecimal.ZERO;
            recordFill(orderId, qty, averagePrice, delta);
            return true;
        });
        return Boolean.TRUE.equals(recorded);
    }

    @Override
    public Map<Isin, BigDecimal> netUnits(String agentId) {
        Map<Isin, BigDecimal> out = new HashMap<>();
        jdbc.query("""
                SELECT i.isin, SUM(CASE o.side WHEN 'BUY' THEN f.quantity ELSE -f.quantity END)
                FROM fill f JOIN trade_order o ON o.id = f.order_id JOIN instrument i ON i.id = o.instrument_id
                WHERE o.agent_id = ? GROUP BY i.isin""",
                rs -> {
                    out.put(new Isin(rs.getString(1).trim()), rs.getBigDecimal(2));
                }, agentId);
        return out;
    }

    @Override
    public Map<Isin, BigDecimal> netUnitsAll() {
        Map<Isin, BigDecimal> out = new HashMap<>();
        jdbc.query("""
                SELECT i.isin, SUM(CASE o.side WHEN 'BUY' THEN f.quantity ELSE -f.quantity END)
                FROM fill f JOIN trade_order o ON o.id = f.order_id JOIN instrument i ON i.id = o.instrument_id
                GROUP BY i.isin""",
                rs -> {
                    out.put(new Isin(rs.getString(1).trim()), rs.getBigDecimal(2));
                });
        return out;
    }

    @Override
    public BigDecimal committedAmount(String agentId) {
        BigDecimal filled = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE o.side WHEN 'BUY' THEN f.amount_eur ELSE -f.amount_eur END), 0)
                FROM fill f JOIN trade_order o ON o.id = f.order_id
                WHERE o.agent_id = ?""", BigDecimal.class, agentId);
        BigDecimal open = jdbc.queryForObject("""
                SELECT COALESCE(SUM(GREATEST(o.amount_eur
                        - COALESCE((SELECT SUM(f.amount_eur) FROM fill f WHERE f.order_id = o.id), 0), 0)), 0)
                FROM trade_order o
                WHERE o.agent_id = ? AND o.side = 'BUY' AND o.state IN ('SUBMITTED', 'PARTIALLY_FILLED')""",
                BigDecimal.class, agentId);
        return (filled == null ? BigDecimal.ZERO : filled).add(open == null ? BigDecimal.ZERO : open);
    }

    private static OrderRecord record(ResultSet rs) throws SQLException {
        OrderRequest req = new OrderRequest(rs.getObject(1, UUID.class), rs.getString(2), new Isin(rs.getString(3).trim()),
                OrderRequest.Side.valueOf(rs.getString(4)), rs.getBigDecimal(5), rs.getBigDecimal(6));
        return new OrderRecord(req, OrderState.valueOf(rs.getString(7)), rs.getString(8));
    }
}
