package com.adrianrevilla.trading.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.brokersim.SimulatedBroker;
import com.adrianrevilla.trading.core.Bar;
import com.adrianrevilla.trading.core.Broker;
import com.adrianrevilla.trading.core.BrokerException;
import com.adrianrevilla.trading.core.BrokerOrder;
import com.adrianrevilla.trading.core.BrokerUnavailableException;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.MarketSnapshot;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderState;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.Strategy;
import com.adrianrevilla.trading.core.TargetPortfolio;
import com.adrianrevilla.trading.data.BarRepository;
import com.adrianrevilla.trading.data.DataQualityValidator;
import com.adrianrevilla.trading.data.IngestionService;
import com.adrianrevilla.trading.data.XetraCalendar;
import com.adrianrevilla.trading.execution.ExecutionService;
import com.adrianrevilla.trading.execution.Reconciler;
import com.adrianrevilla.trading.execution.RealTradingGate;
import com.adrianrevilla.trading.risk.RiskConfig;
import com.adrianrevilla.trading.risk.RiskManager;
import com.adrianrevilla.trading.strategies.BuyAndHold;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The whole daily loop on a real PostgreSQL and a simulated broker: decide, risk, send, reconcile, record. */
@Testcontainers
class TradingDayJobTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private static final LocalDate D1 = LocalDate.parse("2025-06-12");
    private static final LocalDate D2 = LocalDate.parse("2025-06-13");
    private static final String AGENT = "hold";

    private PGSimpleDataSource ds;
    private BarRepository bars;
    private JdbcOrderRepository orders;
    private JdbcKillSwitch kill;
    private JdbcTradingDayLog dayLog;
    private JdbcArenaJournal journal;
    private SimulatedBroker sim;
    private final List<String> alerts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        bars = new BarRepository(ds);
        orders = new JdbcOrderRepository(ds);
        kill = new JdbcKillSwitch(ds);
        dayLog = new JdbcTradingDayLog(ds);
        journal = new JdbcArenaJournal(ds);
        sim = new SimulatedBroker(isin -> BigDecimal.ZERO, new BigDecimal("100"));
        alerts.clear();
        ingest(A, "2025-06-02", "2025-06-13");
        ingest(B, "2025-06-02", "2025-06-13");
    }

    private void ingest(Isin isin, String from, String to) {
        var service = new IngestionService(bars, new DataQualityValidator(new XetraCalendar()));
        List<Bar> data = new ArrayList<>();
        for (LocalDate d : new XetraCalendar().tradingDays(LocalDate.parse(from), LocalDate.parse(to))) {
            BigDecimal p = BigDecimal.valueOf(100);
            data.add(new Bar(d, p, p, p, p, 1_000_000));
        }
        service.ingest(isin, "XETRA", "csv", data, List.of("csv"));
    }

    private TradingDayJob job(Broker broker, Strategy strategy, boolean real, RealTradingGate gate) {
        var execution = new ExecutionService(broker, orders, kill, (l, m) -> alerts.add(l + ":" + m),
                new Reconciler(new BigDecimal("0.000001")));
        var risk = RiskManager.standard(RiskConfig.defaults(Set.of(A, B)), kill);
        return new TradingDayJob(AGENT, strategy, List.of(A, B), "XETRA", bars, execution, orders, broker, kill, risk,
                dayLog, journal, (l, m) -> alerts.add(l + ":" + m), real, gate);
    }

    private TradingDayJob job() {
        return job(sim, new BuyAndHold("hold", A), false, RealTradingGate.closed());
    }

    private static final class ExplodingStrategy implements Strategy {
        @Override public String id() { return "boom"; }
        @Override public TargetPortfolio decide(MarketSnapshot s, PortfolioState st) {
            throw new IllegalStateException("strategy bug");
        }
    }

    /** The order reaches the broker but the answer is lost: the classic unknown outcome. */
    private static final class ResponseLostBroker implements Broker {
        private final Broker inner;

        ResponseLostBroker(Broker inner) { this.inner = inner; }

        @Override public void submit(OrderRequest r) throws BrokerException {
            inner.submit(r);
            throw new BrokerUnavailableException("timeout after delivery");
        }
        @Override public void cancel(UUID id) throws BrokerException { inner.cancel(id); }
        @Override public Optional<BrokerOrder> lookup(UUID id) throws BrokerException { return inner.lookup(id); }
        @Override public Map<Isin, BigDecimal> positions() throws BrokerException { return inner.positions(); }
        @Override public BigDecimal cash() throws BrokerException { return inner.cash(); }
    }

    @Test
    void happyPathTradesTheRiskTrimmedAmountAndTheNextDayReconcilesTheFill() {
        var result = job().run(D1);
        assertThat(result.outcome()).isEqualTo(TradingDayJob.Outcome.TRADED);
        assertThat(result.sent()).isEqualTo(1);
        // BuyAndHold wants 100 %, the risk limit caps one instrument at 25 % of 100 EUR
        var order = orders.findOpen().get(0).request();
        assertThat(order.amountEur()).isEqualByComparingTo("25");
        assertThat(dayLog.status(D1, AGENT)).contains("DONE");

        sim.executePending(Map.of(A, BigDecimal.valueOf(100), B, BigDecimal.valueOf(100))); // next open
        var next = job().run(D2);
        assertThat(next.outcome()).isEqualTo(TradingDayJob.Outcome.TRADED);
        assertThat(next.sent()).isZero(); // already at the capped weight
        assertThat(orders.netUnits(AGENT).get(A)).isEqualByComparingTo("0.25");
        assertThat(orders.findOpen()).isEmpty();
        assertThat(alerts).noneMatch(a -> a.startsWith("CRITICAL"));
        assertThat(journal.equity(AGENT)).containsKeys(D1, D2);
    }

    @Test
    void runningTheSameDayAgainDoesNothing() {
        job().run(D1);
        var again = job().run(D1);
        assertThat(again.outcome()).isEqualTo(TradingDayJob.Outcome.ALREADY_RAN);
        assertThat(sim.orderCount()).isEqualTo(1);
    }

    @Test
    void severalThreadsRunningTheSameDayTradeExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TradingDayJob.Result>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return job().run(D1);
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        long traded = 0;
        for (var f : futures) {
            if (f.get().outcome() == TradingDayJob.Outcome.TRADED) {
                traded++;
            }
        }
        assertThat(traded).isEqualTo(1);
        assertThat(sim.orderCount()).isEqualTo(1);
    }

    @Test
    void anActiveKillSwitchSendsNothing() {
        kill.activate("test");
        var result = job().run(D1);
        assertThat(result.outcome()).isEqualTo(TradingDayJob.Outcome.KILL_SWITCH_ACTIVE);
        assertThat(sim.orderCount()).isZero();
        assertThat(dayLog.status(D1, AGENT)).isEmpty();
    }

    @Test
    void missingDataSkipsTheDayWithoutClaimingItSoItCanRunLater() {
        ingest(B, "2025-06-02", "2025-06-11"); // B lacks 12 and 13 June
        // re-ingesting the shorter series keeps the old raw rows, so use a fresh instrument without recent bars
        bars.replaceClean(bars.findInstrumentId(B, "XETRA").orElseThrow(), bars.loadClean(
                bars.findInstrumentId(B, "XETRA").orElseThrow()).stream().filter(b -> b.date().isBefore(D1)).toList());
        var result = job().run(D1);
        assertThat(result.outcome()).isEqualTo(TradingDayJob.Outcome.NO_DATA);
        assertThat(sim.orderCount()).isZero();
        assertThat(dayLog.status(D1, AGENT)).isEmpty();
        assertThat(alerts).anyMatch(a -> a.startsWith("WARNING:No data"));
    }

    @Test
    void realModeNeedsTheGateAndPaperModeDoesNot() {
        var denied = job(sim, new BuyAndHold("hold", A), true, RealTradingGate.closed()).run(D1);
        assertThat(denied.outcome()).isEqualTo(TradingDayJob.Outcome.NOT_ALLOWED);
        assertThat(sim.orderCount()).isZero();
        var allowed = job(sim, new BuyAndHold("hold", A), true, new RealTradingGate(true, Set.of(AGENT))).run(D1);
        assertThat(allowed.outcome()).isEqualTo(TradingDayJob.Outcome.TRADED);
    }

    @Test
    void aLostResponseIsAnUnknownOutcomeThatTheNextRunResolvesWithoutResending() {
        var lost = new ResponseLostBroker(sim);
        var first = job(lost, new BuyAndHold("hold", A), false, RealTradingGate.closed()).run(D1);
        assertThat(first.unknown()).isEqualTo(1);
        assertThat(sim.orderCount()).isEqualTo(1); // it did arrive

        sim.executePending(Map.of(A, BigDecimal.valueOf(100), B, BigDecimal.valueOf(100)));
        var second = job(lost, new BuyAndHold("hold", A), false, RealTradingGate.closed()).run(D2);
        assertThat(second.outcome()).isEqualTo(TradingDayJob.Outcome.TRADED);
        assertThat(sim.orderCount()).as("never resent").isEqualTo(1);
        assertThat(orders.netUnits(AGENT).get(A)).isEqualByComparingTo("0.25");
        assertThat(orders.findOpen()).isEmpty();
    }

    @Test
    void aBrokerPositionThatTheLedgerDoesNotKnowHaltsEverything() throws Exception {
        // something outside the bot bought units at the broker
        sim.submit(new OrderRequest(UUID.randomUUID(), "outside", A, OrderRequest.Side.BUY, BigDecimal.TEN, null));
        sim.executePending(Map.of(A, BigDecimal.valueOf(100)));
        var result = job().run(D1);
        assertThat(result.outcome()).isEqualTo(TradingDayJob.Outcome.HALTED);
        assertThat(kill.isActive()).isTrue();
        assertThat(sim.orderCount()).isEqualTo(1); // only the outside order; the bot sent nothing
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:TRADING HALTED"));
    }

    @Test
    void aFailingStrategyIsRecordedAsFailedAndNeverRetriedAutomatically() {
        var failing = job(sim, new ExplodingStrategy(), false, RealTradingGate.closed());
        assertThat(failing.run(D1).outcome()).isEqualTo(TradingDayJob.Outcome.FAILED);
        assertThat(dayLog.status(D1, AGENT)).contains("FAILED");
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:Agent hold failed"));
        assertThat(job().run(D1).outcome()).isEqualTo(TradingDayJob.Outcome.ALREADY_RAN);
        assertThat(sim.orderCount()).isZero();
    }

    @Test
    void aDayLeftInStartedByACrashIsNotRetriedAndRaisesACriticalAlert() {
        assertThat(dayLog.claim(D1, AGENT)).isTrue(); // a previous process claimed it and died
        var result = job().run(D1);
        assertThat(result.outcome()).isEqualTo(TradingDayJob.Outcome.ALREADY_RAN);
        assertThat(sim.orderCount()).isZero();
        assertThat(alerts).anyMatch(a -> a.startsWith("CRITICAL:") && a.contains("never finished"));
    }

    @Test
    void theOrderIsPersistedBeforeItIsSentAndItsStateIsFollowed() {
        job().run(D1);
        var open = orders.findOpen();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).state()).isEqualTo(OrderState.SUBMITTED);
    }
}
