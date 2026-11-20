package com.adrianrevilla.trading.adapter.out.persistence;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.tax.application.port.out.TaxTradeSource;
import com.adrianrevilla.trading.tax.domain.TaxTrade;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the executed trades of an agent from the ledger, in chronological order, for the TaxLedger. */
public class JdbcTaxTradeSource implements TaxTradeSource {
    private final JdbcTemplate jdbc;

    public JdbcTaxTradeSource(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public List<TaxTrade> trades(String agentId) {
        return jdbc.query("""
                SELECT f.filled_at, i.isin, o.side, f.quantity, f.amount_eur, f.fee_eur
                FROM fill f JOIN trade_order o ON o.id = f.order_id JOIN instrument i ON i.id = o.instrument_id
                WHERE o.agent_id = ? ORDER BY f.filled_at, f.created_at, f.id""",
                (rs, n) -> new TaxTrade(
                        rs.getTimestamp(1).toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                        new Isin(rs.getString(2).trim()),
                        TaxTrade.Side.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)),
                agentId);
    }
}
