package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.brokersim.SimulatedBroker;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderRequest.Side;
import com.adrianrevilla.trading.core.OrderState;
import com.adrianrevilla.trading.data.BarRepository;
import com.adrianrevilla.trading.execution.ExecutionService;
import com.adrianrevilla.trading.execution.OrderRepository;
import com.adrianrevilla.trading.execution.Reconciler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ExecutionPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    private PGSimpleDataSource ds;
    private JdbcOrderRepository repo;
    private JdbcKillSwitch kill;

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        new BarRepository(ds).ensureInstrument(ISIN, "XETRA", "EUR", "ACC");
        repo = new JdbcOrderRepository(ds);
        kill = new JdbcKillSwitch(ds);
    }

    private static OrderRequest buy(String amount) {
        return new OrderRequest(UUID.randomUUID(), "agent", ISIN, Side.BUY, new BigDecimal(amount), null);
    }

    @Test
    void fullFlowAgainstPostgres() {
        SimulatedBroker sim = new SimulatedBroker(i -> BigDecimal.ZERO, new BigDecimal("1000"));
        List<String> alerts = new ArrayList<>();
        var service = new ExecutionService(sim, repo, kill, (l, m) -> alerts.add(m), new Reconciler(new BigDecimal("0.000001")));
        var order = buy("100");

        assertThat(service.send(order)).isEqualTo(ExecutionService.SendOutcome.SUBMITTED);
        assertThat(repo.find(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.SUBMITTED);
        sim.executePending(Map.of(ISIN, new BigDecimal("10")));
        service.syncOpenOrders();

        assertThat(repo.find(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
        assertThat(repo.netUnits("agent").get(ISIN)).isEqualByComparingTo("10");
        assertThat(repo.recordedFillAmount(order.internalId())).isEqualByComparingTo("100");
        assertThat(service.reconcile("agent").ok()).isTrue();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void duplicateInternalIdIsRefusedByTheDatabase() {
        var o = buy("10");
        repo.create(o);
        assertThatThrownBy(() -> repo.create(o)).isInstanceOf(OrderRepository.DuplicateOrderException.class);
    }

    @Test
    void illegalTransitionIsRefusedAndFillsAreAppendOnly() {
        var o = buy("10");
        repo.create(o);
        assertThatThrownBy(() -> repo.updateState(o.internalId(), OrderState.FILLED, "x"))
                .isInstanceOf(IllegalStateException.class);
        repo.updateState(o.internalId(), OrderState.SUBMITTED, "ok");
        repo.recordFill(o.internalId(), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN);
        assertThatThrownBy(() -> new JdbcTemplate(ds).update("UPDATE fill SET quantity = 2"))
                .hasMessageContaining("append-only");
    }

    @Test
    void killSwitchSurvivesAndStoresTheReason() {
        assertThat(kill.isActive()).isFalse();
        kill.activate("manual test");
        assertThat(new JdbcKillSwitch(ds).isActive()).isTrue(); // a new instance reads the same flag
        assertThat(kill.reason()).isEqualTo("manual test");
        kill.deactivate();
        assertThat(kill.isActive()).isFalse();
    }

    @Test
    void brokerReferenceIsStoredWithTheOrderAndSurvivesANewInstance() {
        var o = buy("10");
        repo.create(o);
        new JdbcBrokerRefStore(ds).put(o.internalId(), "E-77");
        assertThat(new JdbcBrokerRefStore(ds).get(o.internalId())).contains("E-77");
        assertThat(new JdbcBrokerRefStore(ds).get(UUID.randomUUID())).isEmpty();
        assertThatThrownBy(() -> new JdbcBrokerRefStore(ds).put(UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ledgerFillsFeedTheTaxLedger() {
        var buyOrder = buy("100");
        repo.create(buyOrder);
        repo.updateState(buyOrder.internalId(), OrderState.SUBMITTED, "ok");
        repo.recordFill(buyOrder.internalId(), new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("100"));
        var sellOrder = new OrderRequest(UUID.randomUUID(), "agent", ISIN, Side.SELL, new BigDecimal("120"), null);
        repo.create(sellOrder);
        repo.updateState(sellOrder.internalId(), OrderState.SUBMITTED, "ok");
        repo.recordFill(sellOrder.internalId(), new BigDecimal("10"), new BigDecimal("12"), new BigDecimal("120"));

        var trades = new JdbcTaxTradeSource(ds).trades("agent");
        assertThat(trades).hasSize(2);
        var report = new com.adrianrevilla.trading.tax.TaxLedger().compute(trades, java.time.LocalDate.now().plusYears(1));
        assertThat(report.sales()).hasSize(1);
        assertThat(report.sales().get(0).gainLoss()).isEqualByComparingTo("20");
    }

    @Test
    void unknownInstrumentCannotBeOrdered() {
        var o = new OrderRequest(UUID.randomUUID(), "agent", new Isin("IE00B3XXRP09"), Side.BUY, BigDecimal.TEN, null);
        assertThatThrownBy(() -> repo.create(o)).isInstanceOf(IllegalArgumentException.class);
    }
}
