package com.adrianrevilla.trading.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.InMemoryKillSwitch;
import com.adrianrevilla.trading.core.Isin;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderRequest.Side;
import com.adrianrevilla.trading.core.PortfolioState;
import com.adrianrevilla.trading.core.RiskContext;
import com.adrianrevilla.trading.core.RiskRule.Decision;
import com.adrianrevilla.trading.core.TargetPortfolio;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskManagerTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");
    private static final Isin OUT = new Isin("IE00BK5BQT80");

    private final InMemoryKillSwitch kill = new InMemoryKillSwitch();
    private final RiskConfig cfg = RiskConfig.defaults(Set.of(A, B));
    private final RiskManager manager = RiskManager.standard(cfg, kill);

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** 100 EUR in cash, price 10 for everything, liquid, market open, no losses. */
    private static RiskContext ctx() {
        return ctx(Map.of(), "100", "100", "100", Set.of(), true);
    }

    private static RiskContext ctx(Map<Isin, BigDecimal> units, String cash, String dayStart, String peak,
            Set<String> sent, boolean open) {
        Map<Isin, BigDecimal> prices = Map.of(A, bd("10"), B, bd("10"), OUT, bd("10"));
        Map<Isin, BigDecimal> adv = Map.of(A, bd("1000000"), B, bd("1000000"), OUT, bd("1000000"));
        return new RiskContext(new PortfolioState(units, bd(cash)), prices, adv, bd(dayStart), bd(peak), sent, open);
    }

    private static TargetPortfolio target(Isin i, String w) {
        return new TargetPortfolio(Map.of(i, bd(w)));
    }

    private static OrderRequest order(Isin i, Side side, String amount, String limit) {
        return new OrderRequest(UUID.randomUUID(), "agent", i, side, bd(amount), limit == null ? null : bd(limit));
    }

    @Test
    void trimsABuyThatWouldBreakTheWeightCap() {
        var review = manager.review("agent", target(A, "0.6"), ctx());
        var r = review.orders().get(0);
        assertThat(r.decision()).isInstanceOf(Decision.Trim.class);
        assertThat(r.finalOrder().amountEur()).isEqualByComparingTo("25");
        assertThat(r.rule()).isEqualTo("max-weight-per-asset");
    }

    @Test
    void rejectsInstrumentsOutsideTheWhitelist() {
        var review = manager.review("agent", target(OUT, "0.2"), ctx());
        assertThat(review.orders().get(0).decision()).isInstanceOf(Decision.Reject.class);
        assertThat(review.approved()).isEmpty();
    }

    @Test
    void dailyLossBlocksBuysButAllowsSells() {
        // day started at 100, now 97: -3 % beyond the 2 % limit. Holds 5 units of A (50 EUR) + 47 cash
        var c = ctx(Map.of(A, bd("5")), "47", "100", "100", Set.of(), true);
        var buy = manager.review("agent", new TargetPortfolio(Map.of(A, bd("0.5"), B, bd("0.2"))), c);
        assertThat(buy.orders().stream().filter(o -> o.proposed().side() == Side.BUY).map(RiskManager.Reviewed::decision))
                .allMatch(d -> d instanceof Decision.Reject);
        var sell = manager.review("agent", TargetPortfolio.allCash(), c);
        assertThat(sell.approved()).hasSize(1);
        assertThat(sell.approved().get(0).side()).isEqualTo(Side.SELL);
    }

    @Test
    void drawdownShutsTheAgentDownAndBlocksEverything() {
        // equity 80 against a peak of 100: 20 % > 15 %
        var c = ctx(Map.of(A, bd("2")), "60", "80", "100", Set.of(), true);
        var review = manager.review("agent", target(B, "0.2"), c);
        assertThat(review.shutdownAgent()).isTrue();
        assertThat(review.shutdownReason()).contains("drawdown");
        assertThat(review.approved()).isEmpty();
    }

    @Test
    void fatFingerPriceIsRejected() {
        var rule = Rules.fatFinger(cfg);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "10.2"), ctx())).isInstanceOf(Decision.Approve.class);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "12"), ctx())).isInstanceOf(Decision.Reject.class);
    }

    @Test
    void duplicateIntentionOfTheDayIsRejected() {
        var c = ctx(Map.of(), "100", "100", "100", Set.of(RiskContext.key(A, Side.BUY)), true);
        assertThat(manager.review("agent", target(A, "0.2"), c).approved()).isEmpty();
    }

    @Test
    void closedMarketDefersInsteadOfRejecting() {
        var c = ctx(Map.of(), "100", "100", "100", Set.of(), false);
        var r = manager.review("agent", target(A, "0.2"), c).orders().get(0);
        assertThat(r.decision()).isInstanceOf(Decision.Defer.class);
    }

    @Test
    void liquidityTrimsOrdersLargerThanTheParticipationLimit() {
        Map<Isin, BigDecimal> prices = Map.of(A, bd("10"));
        var c = new RiskContext(new PortfolioState(Map.of(), bd("100")), prices, Map.of(A, bd("200")), bd("100"),
                bd("100"), Set.of(), true);
        var d = Rules.liquidity(cfg).evaluate(order(A, Side.BUY, "20", null), c);
        assertThat(d).isInstanceOf(Decision.Trim.class);
        assertThat(((Decision.Trim) d).newAmountEur()).isEqualByComparingTo("10"); // 5 % of 200
    }

    @Test
    void killSwitchBlocksAnyNewOrder() {
        kill.activate("test");
        var review = manager.review("agent", target(A, "0.2"), ctx());
        assertThat(review.approved()).isEmpty();
        assertThat(review.orders().get(0).rule()).isEqualTo("kill-switch");
        kill.deactivate();
        assertThat(manager.review("agent", target(A, "0.2"), ctx()).approved()).hasSize(1);
    }

    @Test
    void severalOrdersInOneReviewAreJudgedCumulatively() {
        var cfg2 = new RiskConfig(bd("0.9"), bd("0.5"), bd("0.02"), bd("0.15"), bd("0.5"), BigDecimal.ONE, bd("0.05"),
                Set.of(A, B));
        var m = RiskManager.standard(cfg2, kill);
        var review = m.review("agent", new TargetPortfolio(Map.of(A, bd("0.4"), B, bd("0.4"))), ctx());
        BigDecimal total = review.approved().stream().map(OrderRequest::amountEur).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isLessThanOrEqualTo(new BigDecimal("50.000001")); // exposure cap 50 %
    }

    @Test
    void sellsAreSentBeforeBuys() {
        var c = ctx(Map.of(A, bd("5")), "50", "100", "100", Set.of(), true);
        var review = manager.review("agent", target(B, "0.2"), c);
        assertThat(review.orders().get(0).proposed().side()).isEqualTo(Side.SELL);
    }

    @Test
    void accountLimitsCatchHiddenConcentrationAcrossAgents() {
        var a1 = new PortfolioState(Map.of(A, bd("3")), bd("70")); // 30 in A
        var a2 = new PortfolioState(Map.of(A, bd("3")), bd("70")); // 30 in A
        var violations = new AccountLimits(bd("0.25")).check(Map.of("a1", a1, "a2", a2), Map.of(A, bd("10")));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).weight()).isEqualByComparingTo("0.3");
    }
}
