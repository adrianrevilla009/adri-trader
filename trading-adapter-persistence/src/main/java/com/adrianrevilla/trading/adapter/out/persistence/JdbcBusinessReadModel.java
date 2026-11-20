package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.application.port.out.BusinessReadModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read side: aggregate queries over the ledger, the orders and the arena journal. Never writes. */
public class JdbcBusinessReadModel implements BusinessReadModel {
    private final JdbcTemplate jdbc;

    public JdbcBusinessReadModel(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<AgentEquity> agentEquity() {
        return jdbc.query("""
                SELECT a.agent_id, a.equity, a.status, p.peak
                FROM (SELECT DISTINCT ON (agent_id) agent_id, equity, status
                      FROM arena_agent_day ORDER BY agent_id, day DESC) a
                JOIN (SELECT agent_id, max(equity) AS peak FROM arena_agent_day GROUP BY agent_id) p USING (agent_id)
                """, (rs, n) -> new AgentEquity(rs.getString("agent_id"), rs.getBigDecimal("equity"),
                rs.getBigDecimal("peak"), rs.getString("status")));
    }

    @Override
    public Map<String, BigDecimal> cashByAgent() {
        return amounts("SELECT agent_id, sum(amount_eur) FROM cash_ledger GROUP BY agent_id");
    }

    @Override
    public Map<String, BigDecimal> investedCostByAgent() {
        return amounts("""
                SELECT o.agent_id, sum(CASE WHEN o.side = 'BUY' THEN f.amount_eur ELSE -f.amount_eur END)
                FROM fill f JOIN trade_order o ON o.id = f.order_id GROUP BY o.agent_id""");
    }

    @Override
    public Map<String, Long> fillsByAgent() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT o.agent_id, count(*) FROM fill f JOIN trade_order o ON o.id = f.order_id GROUP BY o.agent_id",
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    @Override
    public Map<String, BigDecimal> tradedAmountByAgent() {
        return amounts("SELECT o.agent_id, sum(f.amount_eur) FROM fill f JOIN trade_order o ON o.id = f.order_id "
                + "GROUP BY o.agent_id");
    }

    @Override
    public Map<String, BigDecimal> feesByAgent() {
        return amounts("SELECT o.agent_id, sum(f.fee_eur) FROM fill f JOIN trade_order o ON o.id = f.order_id "
                + "GROUP BY o.agent_id");
    }

    @Override
    public List<OrderCount> ordersByAgentAndState() {
        List<OrderCount> out = new ArrayList<>();
        jdbc.query("SELECT agent_id, state, count(*) FROM trade_order GROUP BY agent_id, state", rs -> {
            out.add(new OrderCount(rs.getString(1), rs.getString(2), rs.getLong(3)));
        });
        return out;
    }

    private Map<String, BigDecimal> amounts(String sql) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            BigDecimal v = rs.getBigDecimal(2);
            out.put(rs.getString(1), v == null ? BigDecimal.ZERO : v);
        });
        return out;
    }
}
