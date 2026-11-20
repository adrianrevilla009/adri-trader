package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.execution.application.port.out.BrokerRefStore;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Keeps the eToro order id in trade_order.broker_order_id so the link survives restarts. */
public class JdbcBrokerRefStore implements BrokerRefStore {
    private final JdbcTemplate jdbc;

    public JdbcBrokerRefStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void put(UUID internalId, String brokerOrderId) {
        int n = jdbc.update("UPDATE trade_order SET broker_order_id = ? WHERE id = ?", brokerOrderId, internalId);
        if (n == 0) {
            throw new IllegalStateException("Order not stored before sending: " + internalId);
        }
    }

    @Override
    public Optional<String> get(UUID internalId) {
        return jdbc.query("SELECT broker_order_id FROM trade_order WHERE id = ?", (rs, i) -> rs.getString(1), internalId)
                .stream().filter(s -> s != null && !s.isBlank()).findFirst();
    }
}
