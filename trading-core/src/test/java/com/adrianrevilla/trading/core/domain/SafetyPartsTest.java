package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The small parts the safety of the bot rests on, each tested on its own (mutation testing showed that other modules
 * exercised them but nothing here would notice a change): the kill switch, the order planner, the risk context, the
 * no-look-ahead market snapshot, the cash cap and the portfolio state.
 */
class SafetyPartsTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");

    // ---- kill switch

    @Test
    void theKillSwitchStartsOffAndKeepsTheReasonUntilItIsReleased() {
        var ks = new InMemoryKillSwitch();
        assertThat(ks.isActive()).isFalse();
        assertThat(ks.reason()).isNull();

        ks.activate("drawdown");
        assertThat(ks.isActive()).isTrue();
        assertThat(ks.reason()).isEqualTo("drawdown");

        ks.deactivate();
        assertThat(ks.isActive()).isFalse();
        assertThat(ks.reason()).isNull();
    }

    @Test
    void aKillSwitchWithoutAReasonStillActivates() {
        var ks = new InMemoryKillSwitch();
        ks.activate(null);
        assertThat(ks.isActive()).isTrue();
        assertThat(ks.reason()).isEqualTo("unspecified");
    }

    // ---- portfolio state

    @Test
    void aPositionIsHeldOnlyWithPositiveUnits() {
        var state = new PortfolioState(Map.of(A, new BigDecimal("2"), B, BigDecimal.ZERO), BigDecimal.TEN);
        assertThat(state.holds(A)).isTrue();
        assertThat(state.holds(B)).isFalse();
        assertThat(state.holds(new Isin("IE00BKM4GZ66"))).isFalse();
        assertThat(new PortfolioState(Map.of(A, new BigDecimal("-1")), BigDecimal.TEN).holds(A)).isFalse();
        assertThat(state.units(new Isin("IE00BKM4GZ66"))).isEqualByComparingTo("0");
    }

    // ---- order request

    @Test
    void changingTheAmountKeepsEverythingElse() {
        UUID id = UUID.randomUUID();
        var order = new OrderRequest(id, "agent", A, OrderRequest.Side.SELL, new BigDecimal("50"), new BigDecimal("9"), true);
        var smaller = order.withAmount(new BigDecimal("20"));
        assertThat(smaller).isNotSameAs(order);
        assertThat(smaller.amountEur()).isEqualByComparingTo("20");
        assertThat(smaller.internalId()).isEqualTo(id);
        assertThat(smaller.side()).isEqualTo(OrderRequest.Side.SELL);
        assertThat(smaller.limitPrice()).isEqualByComparingTo("9");
        assertThat(smaller.closePosition()).isTrue();
        assertThat(order.amountEur()).isEqualByComparingTo("50"); // the original is untouched
    }

    // ---- order planner

    private static List<OrderRequest> plan(Map<Isin, BigDecimal> weights, Map<Isin, BigDecimal> units, String cash,
            Map<Isin, BigDecimal> prices, String min) {
        return OrderPlanner.plan("a", new TargetPortfolio(weights), new PortfolioState(units, new BigDecimal(cash)), prices,
                new BigDecimal(min));
    }

    @Test
    void anOrderOfExactlyTheMinimumIsPlannedAndOneCentLessIsNot() {
        var prices = Map.of(A, BigDecimal.TEN);
        // equity 100, weight 0.10 -> a buy of exactly 10
        assertThat(plan(Map.of(A, new BigDecimal("0.10")), Map.of(), "100", prices, "10")).hasSize(1);
        // equity 99.99 -> 9.999: below the minimum of 10
        assertThat(plan(Map.of(A, new BigDecimal("0.10")), Map.of(), "99.99", prices, "10")).isEmpty();
    }

    @Test
    void sellsComeBeforeBuysAndAFullExitClosesThePosition() {
        var prices = Map.of(A, BigDecimal.TEN, B, BigDecimal.TEN);
        var orders = plan(Map.of(B, new BigDecimal("0.5")), Map.of(A, new BigDecimal("5")), "50", prices, "1");
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).side()).isEqualTo(OrderRequest.Side.SELL);
        assertThat(orders.get(0).isin()).isEqualTo(A);
        assertThat(orders.get(0).closePosition()).isTrue();
        assertThat(orders.get(0).amountEur()).isEqualByComparingTo("50");
        assertThat(orders.get(1).side()).isEqualTo(OrderRequest.Side.BUY);
        assertThat(orders.get(1).amountEur()).isEqualByComparingTo("50");
    }

    @Test
    void aPartialReductionSellsTheDifferenceWithoutClosing() {
        var prices = Map.of(A, BigDecimal.TEN);
        var orders = plan(Map.of(A, new BigDecimal("0.5")), Map.of(A, new BigDecimal("10")), "0", prices, "1");
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).side()).isEqualTo(OrderRequest.Side.SELL);
        assertThat(orders.get(0).closePosition()).isFalse();
        assertThat(orders.get(0).amountEur()).isEqualByComparingTo("50");
    }

    @Test
    void aMissingOrZeroPriceMeansNoOrderForThatInstrument() {
        assertThat(plan(Map.of(A, BigDecimal.ONE), Map.of(), "100", Map.of(), "1")).isEmpty();
        assertThat(plan(Map.of(A, BigDecimal.ONE), Map.of(), "100", Map.of(A, BigDecimal.ZERO), "1")).isEmpty();
    }

    // ---- risk context

    @Test
    void theContextValuesPositionsAndAppliesOrdersCumulatively() {
        var state = new PortfolioState(Map.of(A, new BigDecimal("2")), new BigDecimal("100"));
        var ctx = new RiskContext(state, Map.of(A, new BigDecimal("10")), Map.of(), new BigDecimal("120"),
                new BigDecimal("120"), Set.of(), true);
        assertThat(ctx.positionValue(A)).isEqualByComparingTo("20");
        assertThat(ctx.positionValue(B)).isEqualByComparingTo("0");
        assertThat(ctx.investedValue()).isEqualByComparingTo("20");
        assertThat(ctx.equity()).isEqualByComparingTo("120");
        assertThat(RiskContext.key(A, OrderRequest.Side.BUY)).isEqualTo("IE00B4L5Y983|BUY");

        var buy = new OrderRequest(UUID.randomUUID(), "a", A, OrderRequest.Side.BUY, new BigDecimal("30"), null);
        var after = ctx.apply(buy);
        assertThat(after.state().units(A)).isEqualByComparingTo("5");
        assertThat(after.state().cash()).isEqualByComparingTo("70");
        assertThat(after.ordersSentToday()).containsExactly("IE00B4L5Y983|BUY");
        assertThat(ctx.state().cash()).isEqualByComparingTo("100"); // the original is untouched

        var sell = new OrderRequest(UUID.randomUUID(), "a", A, OrderRequest.Side.SELL, new BigDecimal("10"), null);
        var both = after.apply(sell);
        assertThat(both.state().units(A)).isEqualByComparingTo("4");
        assertThat(both.state().cash()).isEqualByComparingTo("80");
        assertThat(both.ordersSentToday()).containsExactlyInAnyOrder("IE00B4L5Y983|BUY", "IE00B4L5Y983|SELL");
    }

    @Test
    void anOrderOnAnInstrumentWithoutAPriceChangesNothingExceptTheSentList() {
        var state = new PortfolioState(Map.of(), new BigDecimal("100"));
        var ctx = new RiskContext(state, Map.of(), Map.of(), new BigDecimal("100"), new BigDecimal("100"), Set.of(), true);
        var after = ctx.apply(new OrderRequest(UUID.randomUUID(), "a", A, OrderRequest.Side.BUY, BigDecimal.TEN, null));
        assertThat(after.state().cash()).isEqualByComparingTo("100");
        assertThat(after.state().units(A)).isEqualByComparingTo("0");
        assertThat(after.ordersSentToday()).hasSize(1);
        assertThat(after.marketOpen()).isTrue();
    }

    // ---- no look-ahead

    private static Bar bar(String date, String availableOn) {
        return new Bar(LocalDate.parse(date), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1,
                LocalDate.parse(availableOn));
    }

    @Test
    void aSnapshotNeverShowsABarFromTheFutureNorOneNotYetAvailable() {
        List<Bar> bars = new ArrayList<>(List.of(bar("2026-01-01", "2026-01-01"), bar("2026-01-02", "2026-01-02"),
                bar("2026-01-05", "2026-01-06"), bar("2026-01-06", "2026-01-06")));
        var snap = new InMemoryMarketSnapshot(LocalDate.parse("2026-01-05"), Map.of(A, bars));

        assertThat(snap.asOf()).isEqualTo(LocalDate.parse("2026-01-05"));
        assertThat(snap.instruments()).containsExactly(A);
        // 2026-01-05 exists but is only available on 01-06; 01-06 is in the future
        assertThat(snap.bars(A)).extracting(Bar::date).containsExactly(LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-02"));
        assertThat(snap.bars(B)).isEmpty();
    }

    @Test
    void aBarOfTheSameDayAndAvailableThatDayIsVisibleAndTheBoundaryIsInclusive() {
        var snap = new InMemoryMarketSnapshot(LocalDate.parse("2026-01-02"),
                Map.of(A, List.of(bar("2026-01-01", "2026-01-01"), bar("2026-01-02", "2026-01-02"),
                        bar("2026-01-03", "2026-01-03"))));
        assertThat(snap.bars(A)).extracting(Bar::date).containsExactly(LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-02"));
    }

    // ---- cash cap

    private record Account(BigDecimal cash, List<UUID> cancelled) implements Broker {
        public void submit(OrderRequest r) { }
        public void cancel(UUID id) { cancelled.add(id); }
        public Optional<BrokerOrder> lookup(UUID id) {
            return Optional.of(new BrokerOrder(id, OrderState.FILLED, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN));
        }
        public Map<Isin, BigDecimal> positions() { return Map.of(A, BigDecimal.ONE); }
        public BigDecimal cash() { return cash; }
    }

    @Test
    void theCashCapNeverReportsMoreThanTheCapitalAndPassesTheRestOn() throws Exception {
        var account = new Account(new BigDecimal("100000"), new ArrayList<>());
        var capped = new CashCappedBroker(account, new BigDecimal("40"));
        assertThat(capped.cash()).isEqualByComparingTo("40");
        assertThat(new CashCappedBroker(new Account(new BigDecimal("12"), new ArrayList<>()), new BigDecimal("40")).cash())
                .isEqualByComparingTo("12");
        UUID id = UUID.randomUUID();
        capped.cancel(id);
        assertThat(account.cancelled()).containsExactly(id);
        assertThat(capped.lookup(id)).isPresent();
        assertThat(capped.positions()).containsKey(A);
    }
}
