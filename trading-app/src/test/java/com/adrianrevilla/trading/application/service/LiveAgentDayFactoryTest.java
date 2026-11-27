package com.adrianrevilla.trading.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.adapter.out.persistence.JdbcArenaJournal;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcBarRepository;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcKillSwitch;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcOrderRepository;
import com.adrianrevilla.trading.adapter.out.persistence.JdbcTradingDayLog;
import com.adrianrevilla.trading.application.port.in.RunTradingDay.Outcome;
import com.adrianrevilla.trading.brokersim.domain.SimulatedBroker;
import com.adrianrevilla.trading.core.domain.AgentConfig;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.CapitalPolicy;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.TradingMode;
import com.adrianrevilla.trading.data.application.IngestionService;
import com.adrianrevilla.trading.data.domain.DataQualityValidator;
import com.adrianrevilla.trading.data.domain.XetraCalendar;
import com.adrianrevilla.trading.execution.application.ExecutionService;
import com.adrianrevilla.trading.execution.application.Reconciler;
import com.adrianrevilla.trading.risk.domain.RiskConfig;
import com.adrianrevilla.trading.strategies.domain.BuiltinStrategies;
import com.adrianrevilla.trading.strategies.domain.StrategyCatalog;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The production factory builds the daily loop of an agent from its stored configuration (ADR-0030). */
@Testcontainers
class LiveAgentDayFactoryTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private static final Isin UNMAPPED = new Isin("IE00BKM4GZ66");
    private static final LocalDate DAY = LocalDate.parse("2025-06-13");
    private static final CapitalPolicy POLICY = new CapitalPolicy(new BigDecimal("10"), new BigDecimal("0.25"));

    private JdbcOrderRepository orders;
    private SimulatedBroker sim;
    private LiveAgentDayFactory factory;
    private final List<String> alerts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        var bars = new JdbcBarRepository(ds);
        orders = new JdbcOrderRepository(ds);
        var kill = new JdbcKillSwitch(ds);
        sim = new SimulatedBroker(isin -> BigDecimal.ZERO, new BigDecimal("100000"));
        alerts.clear();
        var service = new IngestionService(bars, new DataQualityValidator(new XetraCalendar()));
        for (Isin isin : List.of(A, B)) {
            List<Bar> data = new ArrayList<>();
            for (LocalDate d : new XetraCalendar().tradingDays(LocalDate.parse("2025-06-02"), DAY)) {
                BigDecimal p = BigDecimal.valueOf(100);
                data.add(new Bar(d, p, p, p, p, 1_000_000));
            }
            service.ingest(isin, "XETRA", "csv", data, List.of("csv"));
        }
        var execution = new ExecutionService(sim, orders, kill, (l, m) -> alerts.add(l + ":" + m),
                new Reconciler(new BigDecimal("0.000001")));
        factory = new LiveAgentDayFactory(new StrategyCatalog(BuiltinStrategies.all()), bars, execution, orders, sim,
                kill, new JdbcTradingDayLog(ds), new JdbcArenaJournal(ds), (l, m) -> alerts.add(l + ":" + m),
                universe -> RiskConfig.defaults(universe), Set.of(A, B), "XETRA");
    }

    private static AgentConfig agent(String id, String strategy, List<Isin> universe, String capital) {
        return AgentConfig.create(id, strategy, Map.of(), universe, new BigDecimal(capital), List.of(),
                TradingMode.DEMO, POLICY);
    }

    @Test
    void buildsAndRunsABuyAndHoldAgentSizedOnItsOwnCapital() {
        var result = factory.create(agent("hold-a", "buy-and-hold", List.of(A), "40")).run(DAY);

        assertThat(result.outcome()).isEqualTo(Outcome.TRADED);
        // 100 % wanted, capped at 25 % of the capital of the agent (40), not of the 100000 the account holds
        assertThat(orders.findOpen().get(0).request().amountEur()).isEqualByComparingTo("10");
        assertThat(orders.findOpen().get(0).request().agentId()).isEqualTo("hold-a");
    }

    @Test
    void aBiggerCapitalGivesBiggerOrdersAndEachAgentUsesItsOwnBudget() {
        factory.create(agent("small", "buy-and-hold", List.of(A), "40")).run(DAY);
        factory.create(agent("big", "buy-and-hold", List.of(B), "80")).run(DAY);

        assertThat(orders.committedAmount("small")).isEqualByComparingTo("10");
        assertThat(orders.committedAmount("big")).isEqualByComparingTo("20");
    }

    @Test
    void anInstrumentTheBrokerCannotTradeIsRejectedWhenBuildingNotWhenSending() {
        assertThatThrownBy(() -> factory.create(agent("x", "buy-and-hold", List.of(A, UNMAPPED), "40")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(UNMAPPED.value());
    }

    @Test
    void anUnknownStrategyFailsWhenBuilding() {
        assertThatThrownBy(() -> factory.create(agent("x", "magic", List.of(A), "40")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("magic");
    }
}
