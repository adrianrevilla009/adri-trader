package com.adrianrevilla.trading.brokersim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.adrianrevilla.trading.core.BrokerRejectedException;
import com.adrianrevilla.trading.core.ExecutionCosts;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderRequest.Side;
import com.adrianrevilla.trading.core.OrderState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimulatedBrokerTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private static final ExecutionCosts NO_COSTS = isin -> BigDecimal.ZERO;
    private static final ExecutionCosts ONE_PERCENT = isin -> new BigDecimal("0.01");

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static OrderRequest buy(Isin isin, String eur) {
        return new OrderRequest(UUID.randomUUID(), "t", isin, Side.BUY, bd(eur), null);
    }

    private static OrderRequest sell(Isin isin, String eur) {
        return new OrderRequest(UUID.randomUUID(), "t", isin, Side.SELL, bd(eur), null);
    }

    @Test
    void aBuyFillsAtTheOpenAndMovesCashIntoUnits() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        var order = buy(A, "50");
        broker.submit(order);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.cash()).isEqualByComparingTo("50");
        assertThat(broker.positions().get(A)).isEqualByComparingTo("5");
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void costsAlwaysWorkAgainstTheTrader() throws Exception {
        var broker = new SimulatedBroker(ONE_PERCENT, bd("100"));
        broker.submit(buy(A, "100"));
        broker.executePending(Map.of(A, bd("10")));
        BigDecimal units = broker.positions().get(A);
        assertThat(units).isLessThan(bd("10")); // paid 10.10 per unit, so fewer than 10 units
        var sellOrder = new OrderRequest(UUID.randomUUID(), "t", A, Side.SELL, bd("1"), null, true);
        broker.submit(sellOrder);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.cash()).isLessThan(bd("100")); // a round trip at the same price loses money
        assertThat(broker.fills()).allSatisfy(f -> assertThat(f.costEur().signum()).isPositive());
    }

    @Test
    void cashNeverGoesNegativeEvenWhenOrdersAskForMoreThanThereIs() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        broker.submit(buy(A, "80"));
        broker.submit(buy(B, "80"));
        broker.executePending(Map.of(A, bd("10"), B, bd("10")));
        assertThat(broker.cash().signum()).isGreaterThanOrEqualTo(0);
        assertThat(broker.cash()).isLessThan(bd("0.000001"));
    }

    @Test
    void sellsAreExecutedBeforeBuysSoTheirCashIsUsedTheSameDay() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        broker.submit(buy(A, "100"));
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.cash()).isLessThan(bd("0.000001"));
        broker.submit(buy(B, "100")); // submitted first, but needs the cash of the sale
        broker.submit(new OrderRequest(UUID.randomUUID(), "t", A, Side.SELL, bd("100"), null, true));
        broker.executePending(Map.of(A, bd("10"), B, bd("20")));
        assertThat(broker.positions()).doesNotContainKey(A);
        assertThat(broker.positions().get(B)).isEqualByComparingTo("5");
    }

    @Test
    void anOrderWithoutAPriceTodayStaysPendingAndFillsLater() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        var order = buy(A, "50");
        broker.submit(order);
        broker.executePending(Map.of(B, bd("10")));
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.SUBMITTED);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void partialFillsAccumulateAndNeverExceedTheOrder() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        broker.setMaxFillFraction(bd("0.5"));
        var order = buy(A, "60");
        broker.submit(order);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.PARTIALLY_FILLED);
        for (int i = 0; i < 6; i++) {
            broker.executePending(Map.of(A, bd("10")));
        }
        var done = broker.lookup(order.internalId()).orElseThrow();
        assertThat(done.state()).isEqualTo(OrderState.FILLED);
        assertThat(done.filledAmountEur()).isLessThanOrEqualTo(bd("60.000001"));
        assertThat(broker.cash()).isEqualByComparingTo("40");
    }

    @Test
    void executingAgainWithNothingPendingChangesNothing() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        broker.submit(buy(A, "50"));
        broker.executePending(Map.of(A, bd("10")));
        var fillsBefore = broker.fills().size();
        var cashBefore = broker.cash();
        broker.executePending(Map.of(A, bd("10")));
        broker.executePending(Map.of(A, bd("12")));
        assertThat(broker.fills()).hasSize(fillsBefore);
        assertThat(broker.cash()).isEqualByComparingTo(cashBefore);
    }

    @Test
    void sellingMoreThanHeldSellsWhatThereIsAndSellingNothingIsRejected() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        broker.submit(buy(A, "20")); // 2 units
        broker.executePending(Map.of(A, bd("10")));
        var big = sell(A, "500");
        broker.submit(big);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.positions()).doesNotContainKey(A);
        assertThat(broker.cash()).isEqualByComparingTo("100");
        var nothing = sell(A, "10");
        broker.submit(nothing);
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.lookup(nothing.internalId()).orElseThrow().state()).isEqualTo(OrderState.REJECTED);
    }

    @Test
    void duplicateIdsAndNonPositiveAmountsAreRejectedAtSubmission() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        var order = buy(A, "10");
        broker.submit(order);
        assertThatThrownBy(() -> broker.submit(order)).isInstanceOf(BrokerRejectedException.class);
        assertThatThrownBy(() -> broker.submit(buy(A, "0"))).isInstanceOf(BrokerRejectedException.class);
        assertThatThrownBy(() -> broker.submit(buy(A, "-5"))).isInstanceOf(BrokerRejectedException.class);
        assertThat(broker.orderCount()).isEqualTo(1);
    }

    @Test
    void aCancelledOrderNeverFillsAndCancellingTwiceOrUnknownIsHarmless() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        var order = buy(A, "50");
        broker.submit(order);
        broker.cancel(order.internalId());
        broker.cancel(order.internalId());
        broker.cancel(UUID.randomUUID());
        broker.executePending(Map.of(A, bd("10")));
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.CANCELLED);
        assertThat(broker.cash()).isEqualByComparingTo("100");
        assertThat(broker.lookup(UUID.randomUUID())).isEmpty();
    }

    @Test
    void cancellingAFilledOrderDoesNotChangeIt() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("100"));
        var order = buy(A, "50");
        broker.submit(order);
        broker.executePending(Map.of(A, bd("10")));
        broker.cancel(order.internalId());
        assertThat(broker.lookup(order.internalId()).orElseThrow().state()).isEqualTo(OrderState.FILLED);
    }

    @Test
    void concurrentSubmissionsOfTheSameIdAcceptExactlyOne() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("1000"));
        var order = buy(A, "10");
        int threads = 16;
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                go.await();
                try {
                    broker.submit(order);
                    accepted.incrementAndGet();
                } catch (BrokerRejectedException e) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        assertThat(broker.orderCount()).isEqualTo(1);
    }

    @Test
    void concurrentSubmitAndExecuteKeepCashAndUnitsConsistent() throws Exception {
        var broker = new SimulatedBroker(NO_COSTS, bd("1000"));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    broker.submit(buy(A, "5"));
                }
                return null;
            }));
        }
        futures.add(pool.submit(() -> {
            for (int i = 0; i < 100; i++) {
                broker.executePending(Map.of(A, bd("10")));
            }
            return null;
        }));
        for (var f : futures) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        broker.executePending(Map.of(A, bd("10")));
        // 150 orders of 5 EUR at price 10: all filled, value conserved
        assertThat(broker.orderCount()).isEqualTo(150);
        BigDecimal value = broker.cash().add(broker.positions().getOrDefault(A, BigDecimal.ZERO).multiply(bd("10")));
        assertThat(value).isEqualByComparingTo("1000");
        assertThat(broker.cash()).isEqualByComparingTo("250");
    }
}
