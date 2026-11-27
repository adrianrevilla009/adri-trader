package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.OrderState;
import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The two queries that let several agents share one account (ADR-0030). */
@Testcontainers
class JdbcOrderRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");

    private JdbcOrderRepository repo;

    @BeforeEach
    void setUp() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO instrument(isin, exchange, currency, distribution) VALUES (?, 'XETRA', 'EUR', 'ACC')",
                A.value());
        jdbc.update("INSERT INTO instrument(isin, exchange, currency, distribution) VALUES (?, 'XETRA', 'EUR', 'ACC')",
                B.value());
        repo = new JdbcOrderRepository(ds);
    }

    private OrderRequest order(String agent, Isin isin, Side side, String amount) {
        var r = new OrderRequest(UUID.randomUUID(), agent, isin, side, new BigDecimal(amount), null);
        repo.create(r);
        return r;
    }

    private void fill(OrderRequest r, String qty, String price) {
        BigDecimal q = new BigDecimal(qty);
        repo.recordFill(r.internalId(), q, new BigDecimal(price), q.multiply(new BigDecimal(price)));
    }

    @Test
    void emptyDatabaseHasNothingCommittedAndNoUnits() {
        assertThat(repo.committedAmount("a")).isEqualByComparingTo("0");
        assertThat(repo.netUnitsAll()).isEmpty();
    }

    @Test
    void netUnitsAllAddsUpEveryAgent() {
        fill(order("a", A, Side.BUY, "10"), "1", "10");
        fill(order("b", A, Side.BUY, "20"), "2", "10");
        fill(order("b", B, Side.BUY, "10"), "1", "10");
        fill(order("a", A, Side.SELL, "10"), "0.5", "20");

        var all = repo.netUnitsAll();
        assertThat(all.get(A)).isEqualByComparingTo("2.5");
        assertThat(all.get(B)).isEqualByComparingTo("1");
        assertThat(repo.netUnits("a").get(A)).isEqualByComparingTo("0.5");
    }

    @Test
    void committedIsInvestedMoneyMinusSalesOfThatAgentOnly() {
        fill(order("a", A, Side.BUY, "30"), "3", "10");
        fill(order("a", A, Side.SELL, "12"), "0.6", "20");
        fill(order("b", A, Side.BUY, "10"), "1", "10");

        assertThat(repo.committedAmount("a")).isEqualByComparingTo("18");
        assertThat(repo.committedAmount("b")).isEqualByComparingTo("10");
        assertThat(repo.committedAmount("nobody")).isEqualByComparingTo("0");
    }

    @Test
    void anOpenBuyCountsAsCommittedUntilItIsFilled() {
        var o = order("a", A, Side.BUY, "10");
        repo.updateState(o.internalId(), OrderState.SUBMITTED, "sent");
        assertThat(repo.committedAmount("a")).isEqualByComparingTo("10");

        fill(o, "0.5", "10"); // half filled: 5 invested + 5 still open = 10, not 15
        repo.updateState(o.internalId(), OrderState.PARTIALLY_FILLED, "half");
        assertThat(repo.committedAmount("a")).isEqualByComparingTo("10");

        fill(o, "0.5", "10");
        repo.updateState(o.internalId(), OrderState.FILLED, "done");
        assertThat(repo.committedAmount("a")).isEqualByComparingTo("10");
    }

    @Test
    void rejectedOrCreatedOrOpenSellsDoNotCommitMoney() {
        var rejected = order("a", A, Side.BUY, "10");
        repo.updateState(rejected.internalId(), OrderState.SUBMITTED, "sent");
        repo.updateState(rejected.internalId(), OrderState.REJECTED, "no");
        order("a", A, Side.BUY, "10"); // still CREATED
        var sell = order("a", A, Side.SELL, "10");
        repo.updateState(sell.internalId(), OrderState.SUBMITTED, "sent");

        assertThat(repo.committedAmount("a")).isEqualByComparingTo("0");
    }
}
