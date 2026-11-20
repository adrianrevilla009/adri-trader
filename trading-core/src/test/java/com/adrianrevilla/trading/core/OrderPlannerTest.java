package com.adrianrevilla.trading.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderPlannerTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");
    private static final BigDecimal MIN = BigDecimal.ONE;

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static List<OrderRequest> plan(Map<Isin, BigDecimal> weights, Map<Isin, BigDecimal> units, String cash,
            Map<Isin, BigDecimal> prices) {
        return OrderPlanner.plan("agent", new TargetPortfolio(weights), new PortfolioState(units, bd(cash)), prices, MIN);
    }

    @Test
    void fromAllCashItBuysTheTargetWeights() {
        var orders = plan(Map.of(A, bd("0.6"), B, bd("0.4")), Map.of(), "100", Map.of(A, bd("10"), B, bd("20")));
        assertThat(orders).hasSize(2).allSatisfy(o -> assertThat(o.side()).isEqualTo(OrderRequest.Side.BUY));
        assertThat(orders.stream().filter(o -> o.isin().equals(A)).findFirst().orElseThrow().amountEur())
                .isEqualByComparingTo("60");
        assertThat(orders.stream().filter(o -> o.isin().equals(B)).findFirst().orElseThrow().amountEur())
                .isEqualByComparingTo("40");
    }

    @Test
    void sellsAreListedBeforeBuysSoTheCashIsFreedFirst() {
        // holds 10 units of A at 10 = 100 EUR, wants to be 100 % in B
        var orders = plan(Map.of(B, BigDecimal.ONE), Map.of(A, bd("10")), "0", Map.of(A, bd("10"), B, bd("20")));
        assertThat(orders).extracting(OrderRequest::side)
                .containsExactly(OrderRequest.Side.SELL, OrderRequest.Side.BUY);
    }

    @Test
    void leavingAPositionClosesItWholeInsteadOfLeavingDust() {
        var orders = plan(Map.of(), Map.of(A, bd("3.333333")), "5", Map.of(A, bd("10")));
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).closePosition()).isTrue();
        assertThat(orders.get(0).side()).isEqualTo(OrderRequest.Side.SELL);
    }

    @Test
    void differencesBelowTheMinimumAreNotTraded() {
        // equity 100, target 50 % = 50, already holds 49.6 -> delta 0.4 < 1 EUR minimum
        var orders = plan(Map.of(A, bd("0.5")), Map.of(A, bd("4.96")), "50.4", Map.of(A, bd("10")));
        assertThat(orders).isEmpty();
    }

    @Test
    void anInstrumentWithoutAPriceIsSkippedNeverTradedBlind() {
        var orders = plan(Map.of(A, bd("0.5"), B, bd("0.5")), Map.of(), "100", Map.of(A, bd("10")));
        assertThat(orders).extracting(OrderRequest::isin).containsExactly(A);
        assertThat(plan(Map.of(A, BigDecimal.ONE), Map.of(), "100", Map.of(A, BigDecimal.ZERO))).isEmpty();
    }

    @Test
    void emptyEverythingPlansNothing() {
        assertThat(plan(Map.of(), Map.of(), "0", Map.of())).isEmpty();
        assertThat(plan(Map.of(), Map.of(), "100", Map.of(A, bd("10")))).isEmpty();
    }

    @Test
    void everyOrderGetsAFreshInternalIdSoPlanningTwiceNeverCollides() {
        var first = plan(Map.of(A, BigDecimal.ONE), Map.of(), "100", Map.of(A, bd("10")));
        var second = plan(Map.of(A, BigDecimal.ONE), Map.of(), "100", Map.of(A, bd("10")));
        assertThat(first.get(0).internalId()).isNotEqualTo(second.get(0).internalId());
    }

    @Test
    void neverPlansBuysAboveTheEquity() {
        var prices = Map.of(A, bd("10"), B, bd("20"));
        var orders = plan(Map.of(A, bd("0.5"), B, bd("0.5")), Map.of(), "100", prices);
        BigDecimal total = orders.stream().map(OrderRequest::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isLessThanOrEqualTo(bd("100.000001"));
    }
}
