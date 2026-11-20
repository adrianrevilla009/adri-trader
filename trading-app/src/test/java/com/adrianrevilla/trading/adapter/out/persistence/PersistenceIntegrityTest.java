package com.adrianrevilla.trading.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.brokersim.domain.SimulatedBroker;
import com.adrianrevilla.trading.core.domain.Bar;
import com.adrianrevilla.trading.core.domain.InMemoryKillSwitch;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.data.application.port.out.BarRepository;
import com.adrianrevilla.trading.execution.application.ExecutionService;
import com.adrianrevilla.trading.execution.application.Reconciler;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository.DuplicateOrderException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Concurrency, idempotency and atomicity of what is written to PostgreSQL. Real database, no mocks. */
@Testcontainers
class PersistenceIntegrityTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    private PGSimpleDataSource ds;
    private JdbcOrderRepository orders;
    private BarRepository bars;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        ds = new PGSimpleDataSource();
        ds.setUrl(PG.getJdbcUrl());
        ds.setUser(PG.getUsername());
        ds.setPassword(PG.getPassword());
        Flyway flyway = Flyway.configure().dataSource(ds).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        bars = new JdbcBarRepository(ds);
        bars.ensureInstrument(ISIN, "XETRA", "EUR", "ACC");
        orders = new JdbcOrderRepository(ds);
        jdbc = new JdbcTemplate(ds);
    }

    private static OrderRequest buy(String eur) {
        return new OrderRequest(UUID.randomUUID(), "agent", ISIN, Side.BUY, new BigDecimal(eur), null);
    }

    /** Runs the tasks at the same moment on separate threads and returns their futures. */
    private static <T> List<Future<T>> race(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return futures;
    }

    // ---- idempotency ----

    @Test
    void creatingTheSameOrderConcurrentlyStoresItExactlyOnce() throws Exception {
        var order = buy("10");
        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        for (var f : race(12, () -> {
            try {
                orders.create(order);
                created.incrementAndGet();
            } catch (DuplicateOrderException e) {
                duplicates.incrementAndGet();
            }
            return null;
        })) {
            f.get();
        }
        assertThat(created.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_order", Integer.class)).isEqualTo(1);
    }

    @Test
    void sendingTheSameOrderConcurrentlyReachesTheBrokerOnce() throws Exception {
        var broker = new SimulatedBroker(i -> BigDecimal.ZERO, new BigDecimal("1000"));
        var service = new ExecutionService(broker, orders, new InMemoryKillSwitch(), (l, m) -> {},
                new Reconciler(new BigDecimal("0.000001")));
        var order = buy("10");
        AtomicInteger submitted = new AtomicInteger();
        for (var f : race(10, () -> {
            try {
                if (service.send(order) == ExecutionService.SendOutcome.SUBMITTED) {
                    submitted.incrementAndGet();
                }
            } catch (DuplicateOrderException expected) {
                // the loser of the race is refused, never sent
            }
            return null;
        })) {
            f.get();
        }
        assertThat(submitted.get()).isEqualTo(1);
        assertThat(broker.orderCount()).isEqualTo(1);
    }

    @Test
    void syncingTheSameFilledOrderFromSeveralThreadsRecordsTheFillOnce() throws Exception {
        var broker = new SimulatedBroker(i -> BigDecimal.ZERO, new BigDecimal("1000"));
        var service = new ExecutionService(broker, orders, new InMemoryKillSwitch(), (l, m) -> {},
                new Reconciler(new BigDecimal("0.000001")));
        var order = buy("100");
        service.send(order);
        broker.executePending(Map.of(ISIN, new BigDecimal("10")));
        for (var f : race(8, service::syncOpenOrders)) {
            f.get();
        }
        assertThat(orders.recordedFillAmount(order.internalId())).isEqualByComparingTo("100");
        assertThat(orders.netUnits("agent").get(ISIN)).isEqualByComparingTo("10");
        assertThat(orders.find(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void syncingAgainAfterEverythingIsRecordedChangesNothing() {
        var broker = new SimulatedBroker(i -> BigDecimal.ZERO, new BigDecimal("1000"));
        var service = new ExecutionService(broker, orders, new InMemoryKillSwitch(), (l, m) -> {},
                new Reconciler(new BigDecimal("0.000001")));
        var order = buy("100");
        service.send(order);
        broker.executePending(Map.of(ISIN, new BigDecimal("10")));
        assertThat(service.syncOpenOrders()).isEqualTo(1);
        assertThat(service.syncOpenOrders()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fill", Integer.class)).isEqualTo(1);
    }

    @Test
    void ingestingTheSameBarsConcurrentlyLeavesOneCopyOfEverything() throws Exception {
        var service = new com.adrianrevilla.trading.data.application.IngestionService(bars,
                new com.adrianrevilla.trading.data.domain.DataQualityValidator(new com.adrianrevilla.trading.data.domain.XetraCalendar()));
        List<Bar> data = new ArrayList<>();
        for (LocalDate d : new com.adrianrevilla.trading.data.domain.XetraCalendar()
                .tradingDays(LocalDate.parse("2025-06-02"), LocalDate.parse("2025-06-27"))) {
            BigDecimal p = BigDecimal.valueOf(100 + d.getDayOfMonth() % 3);
            data.add(new Bar(d, p, p, p, p, 1000));
        }
        for (var f : race(6, () -> service.ingest(ISIN, "XETRA", "csv", data, List.of("csv")))) {
            f.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instrument", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bar_daily_raw", Integer.class)).isEqualTo(data.size());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bar_daily", Integer.class)).isEqualTo(data.size());
    }

    // ---- state machine under concurrency ----

    @Test
    void twoThreadsCannotBothMoveTheSameOrderToDifferentFinalStates() throws Exception {
        var order = buy("10");
        orders.create(order);
        orders.updateState(order.internalId(), OrderState.SUBMITTED, "sent");
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger turn = new AtomicInteger();
        for (var f : race(2, () -> {
            OrderState target = turn.getAndIncrement() == 0 ? OrderState.FILLED : OrderState.CANCELLED;
            try {
                orders.updateState(order.internalId(), target, "race " + target);
                wins.incrementAndGet();
            } catch (RuntimeException expectedLoser) {
                // refused: the other thread already made the order final
            }
            return null;
        })) {
            f.get();
        }
        assertThat(wins.get()).as("exactly one transition to a final state may win").isEqualTo(1);
    }

    // ---- append-only guarantees ----

    @Test
    void fillsCannotBeChangedOrDeletedAndOrdersCannotBeDeleted() {
        var order = buy("10");
        orders.create(order);
        orders.recordFill(order.internalId(), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN);
        assertThatThrownBy(() -> jdbc.update("UPDATE fill SET amount_eur = 1")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM fill")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM trade_order")).isInstanceOf(DataAccessException.class);
        assertThat(orders.recordedFillAmount(order.internalId())).isEqualByComparingTo("10");
    }

    // ---- atomicity ----

    @Test
    void aFailedReplaceOfTheCleanSeriesLeavesTheOldSeriesUntouched() {
        long id = bars.findInstrumentId(ISIN, "XETRA").orElseThrow();
        Bar ok = new Bar(LocalDate.parse("2025-06-02"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1);
        Bar ok2 = new Bar(LocalDate.parse("2025-06-03"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1);
        bars.replaceClean(id, List.of(ok, ok2));
        // the second bar repeats a date: the primary key fails half way through the batch
        Bar next = new Bar(LocalDate.parse("2025-06-04"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1);
        assertThatThrownBy(() -> bars.replaceClean(id, List.of(next, next))).isInstanceOf(DataAccessException.class);
        assertThat(bars.loadClean(id)).extracting(Bar::date)
                .containsExactly(LocalDate.parse("2025-06-02"), LocalDate.parse("2025-06-03"));
    }

    @Test
    void anOrderForAnUnknownInstrumentIsRefusedAndLeavesNothingBehind() {
        var unknown = new OrderRequest(UUID.randomUUID(), "agent", new Isin("IE00B3XXRP09"), Side.BUY,
                BigDecimal.TEN, null);
        assertThatThrownBy(() -> orders.create(unknown)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_order", Integer.class)).isZero();
    }

    @Test
    void theKillSwitchStateIsConsistentUnderConcurrentFlips() throws Exception {
        var kill = new JdbcKillSwitch(ds);
        AtomicInteger turn = new AtomicInteger();
        for (var f : race(10, () -> {
            for (int i = 0; i < 20; i++) {
                if (turn.getAndIncrement() % 2 == 0) {
                    kill.activate("race");
                } else {
                    kill.deactivate();
                }
            }
            return null;
        })) {
            f.get();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM kill_switch", Integer.class)).isEqualTo(1);
        kill.activate("final");
        assertThat(kill.isActive()).isTrue();
        kill.deactivate();
        assertThat(kill.isActive()).isFalse();
    }
}
