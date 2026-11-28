package com.adrianrevilla.trading.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.RiskContext;
import com.adrianrevilla.trading.core.domain.RiskRule.Decision;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every limit on both sides of its edge. These are the numbers that stop real losses, so the exact comparison
 * (reject at the limit or only above it) is part of the contract and is pinned here.
 */
class RulesBoundaryTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B3XXRP09");
    private final RiskConfig cfg = RiskConfig.defaults(Set.of(A));

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static OrderRequest order(Isin isin, Side side, String amount, String limit) {
        return new OrderRequest(UUID.randomUUID(), "a", isin, side, bd(amount), limit == null ? null : bd(limit));
    }

    private static RiskContext ctx(String cash, Map<Isin, BigDecimal> units, String dayStart, String peak,
            Map<Isin, BigDecimal> adv, Set<String> sent, boolean open) {
        return new RiskContext(new PortfolioState(units, bd(cash)), Map.of(A, bd("10")), adv, bd(dayStart), bd(peak),
                sent, open);
    }

    private static RiskContext cash(String cash) {
        return ctx(cash, Map.of(), cash, cash, Map.of(A, bd("1000000")), Set.of(), true);
    }

    // ---- daily loss: blocks buys when the loss reaches 2 %

    @Test
    void theDailyLossBlocksBuysExactlyAtTheLimitAndNotBelowIt() {
        var rule = Rules.maxDailyLoss(cfg);
        var buy = order(A, Side.BUY, "10", null);
        var atLimit = ctx("98", Map.of(), "100", "100", Map.of(), Set.of(), true); // lost exactly 2 %
        var justBelow = ctx("98.01", Map.of(), "100", "100", Map.of(), Set.of(), true);

        assertThat(rule.evaluate(buy, atLimit)).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) rule.evaluate(buy, atLimit)).reason())
                .isEqualTo("daily loss 2.00% reached limit 2.00%");
        assertThat(rule.evaluate(buy, justBelow)).isInstanceOf(Decision.Approve.class);
    }

    @Test
    void theDailyLossNeverBlocksSellsAndIgnoresADayThatStartedEmpty() {
        var rule = Rules.maxDailyLoss(cfg);
        var lost = ctx("50", Map.of(), "100", "100", Map.of(), Set.of(), true);
        assertThat(rule.evaluate(order(A, Side.SELL, "10", null), lost)).isInstanceOf(Decision.Approve.class);
        var emptyStart = ctx("50", Map.of(), "0", "100", Map.of(), Set.of(), true);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", null), emptyStart)).isInstanceOf(Decision.Approve.class);
    }

    // ---- drawdown: shuts the agent down at 15 % from the peak

    @Test
    void theDrawdownShutsTheAgentDownExactlyAtTheLimit() {
        var rule = Rules.agentDrawdown(cfg);
        assertThat(rule.name()).isEqualTo("agent-drawdown");
        var atLimit = ctx("85", Map.of(), "100", "100", Map.of(), Set.of(), true);
        var below = ctx("85.01", Map.of(), "100", "100", Map.of(), Set.of(), true);

        assertThat(rule.evaluateAgent(atLimit)).isInstanceOf(Decision.Shutdown.class);
        assertThat(((Decision.Shutdown) rule.evaluateAgent(atLimit)).reason())
                .isEqualTo("drawdown 15.00% reached limit 15.00%");
        assertThat(rule.evaluateAgent(below)).isInstanceOf(Decision.Approve.class);
        // judging an order gives the same verdict as judging the agent
        assertThat(rule.evaluate(order(A, Side.BUY, "10", null), atLimit)).isInstanceOf(Decision.Shutdown.class);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", null), below)).isInstanceOf(Decision.Approve.class);
    }

    @Test
    void aPeakOfZeroCannotTriggerTheShutdown() {
        var rule = Rules.agentDrawdown(cfg);
        assertThat(rule.evaluateAgent(ctx("0", Map.of(), "0", "0", Map.of(), Set.of(), true)))
                .isInstanceOf(Decision.Approve.class);
    }

    // ---- fat finger: a limit price more than 5 % from the last price

    @Test
    void aLimitPriceIsRejectedOnlyAboveTheDeviationLimit() {
        var rule = Rules.fatFinger(cfg);
        var c = cash("100");
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "10.5"), c)).isInstanceOf(Decision.Approve.class); // 5.00 %
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "9.5"), c)).isInstanceOf(Decision.Approve.class);
        var tooFar = rule.evaluate(order(A, Side.BUY, "10", "10.51"), c);
        assertThat(tooFar).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) tooFar).reason()).isEqualTo("limit price deviates 5.10% from last price");
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "9.49"), c)).isInstanceOf(Decision.Reject.class);
    }

    @Test
    void noLimitPriceMeansNothingToCheckButALimitWithoutAReferenceIsRejected() {
        var rule = Rules.fatFinger(cfg);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", null), cash("100"))).isInstanceOf(Decision.Approve.class);
        var noPrice = rule.evaluate(order(B, Side.BUY, "10", "10"), cash("100"));
        assertThat(noPrice).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) noPrice).reason()).isEqualTo("no reference price for IE00B3XXRP09");
        var zeroPrice = new RiskContext(new PortfolioState(Map.of(), bd("100")), Map.of(A, BigDecimal.ZERO), Map.of(),
                bd("100"), bd("100"), Set.of(), true);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", "10"), zeroPrice)).isInstanceOf(Decision.Reject.class);
    }

    // ---- liquidity: at most 5 % of the average daily traded value

    @Test
    void liquidityApprovesUpToTheParticipationTrimsAboveAndRejectsWithoutData() {
        var rule = Rules.liquidity(cfg);
        var c = ctx("100000", Map.of(), "100000", "100000", Map.of(A, bd("1000")), Set.of(), true); // room = 50
        assertThat(rule.evaluate(order(A, Side.BUY, "50", null), c)).isInstanceOf(Decision.Approve.class);
        var trimmed = rule.evaluate(order(A, Side.BUY, "50.01", null), c);
        assertThat(trimmed).isInstanceOf(Decision.Trim.class);
        assertThat(((Decision.Trim) trimmed).newAmountEur()).isEqualByComparingTo("50");
        assertThat(((Decision.Trim) trimmed).reason()).isEqualTo("liquidity 0.05 of ADV");

        var none = rule.evaluate(order(B, Side.BUY, "10", null), c);
        assertThat(none).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) none).reason()).isEqualTo("no liquidity data for IE00B3XXRP09");
        var dry = ctx("100", Map.of(), "100", "100", Map.of(A, BigDecimal.ZERO), Set.of(), true);
        assertThat(rule.evaluate(order(A, Side.BUY, "10", null), dry)).isInstanceOf(Decision.Reject.class);
    }

    // ---- the shared limit helper through the weight and exposure caps

    @Test
    void theWeightCapApprovesExactlyTheRoomAndTrimsToTheRoomRoundedDown() {
        var rule = Rules.maxWeightPerAsset(cfg);
        var c = cash("100"); // equity 100, cap 25 % -> room 25
        assertThat(rule.evaluate(order(A, Side.BUY, "25", null), c)).isInstanceOf(Decision.Approve.class);
        var trimmed = rule.evaluate(order(A, Side.BUY, "25.000001", null), c);
        assertThat(trimmed).isInstanceOf(Decision.Trim.class);
        assertThat(((Decision.Trim) trimmed).newAmountEur()).isEqualByComparingTo("25");
        assertThat(((Decision.Trim) trimmed).reason()).isEqualTo("weight cap 0.25");

        var odd = cash("33.333333333"); // room 8.33333333325 -> rounded DOWN to 6 decimals
        var t2 = (Decision.Trim) rule.evaluate(order(A, Side.BUY, "20", null), odd);
        assertThat(t2.newAmountEur()).isEqualByComparingTo("8.333333");
    }

    @Test
    void withNoRoomLeftABuyIsRejectedAndASellIsNeverLimited() {
        var rule = Rules.maxWeightPerAsset(cfg);
        var full = ctx("75", Map.of(A, bd("2.5")), "100", "100", Map.of(), Set.of(), true); // A is exactly 25 of 100
        var reject = rule.evaluate(order(A, Side.BUY, "1", null), full);
        assertThat(reject).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) reject).reason()).isEqualTo("no room: weight cap 0.25");
        var over = ctx("60", Map.of(A, bd("4")), "100", "100", Map.of(), Set.of(), true); // A is 40 of 100
        assertThat(rule.evaluate(order(A, Side.BUY, "1", null), over)).isInstanceOf(Decision.Reject.class);
        assertThat(rule.evaluate(order(A, Side.SELL, "30", null), over)).isInstanceOf(Decision.Approve.class);
    }

    @Test
    void theExposureCapRejectsWhenEverythingIsInvested() {
        var cfgCap = new RiskConfig(bd("1"), bd("0.5"), bd("0.02"), bd("0.15"), bd("0.05"), BigDecimal.ONE,
                bd("0.05"), Set.of(A));
        var rule = Rules.maxExposure(cfgCap);
        var half = ctx("50", Map.of(A, bd("5")), "100", "100", Map.of(), Set.of(), true); // 50 invested of 100
        assertThat(rule.evaluate(order(A, Side.BUY, "1", null), half)).isInstanceOf(Decision.Reject.class);
        assertThat(rule.evaluate(order(A, Side.SELL, "1", null), half)).isInstanceOf(Decision.Approve.class);
        var room = ctx("100", Map.of(), "100", "100", Map.of(), Set.of(), true);
        assertThat(rule.evaluate(order(A, Side.BUY, "50", null), room)).isInstanceOf(Decision.Approve.class);
        assertThat(rule.evaluate(order(A, Side.BUY, "51", null), room)).isInstanceOf(Decision.Trim.class);
    }

    // ---- the simple rules

    @Test
    void whitelistDuplicateAndMarketHours() {
        assertThat(Rules.whitelist(cfg).evaluate(order(A, Side.BUY, "1", null), cash("10")))
                .isInstanceOf(Decision.Approve.class);
        var out = Rules.whitelist(cfg).evaluate(order(B, Side.BUY, "1", null), cash("10"));
        assertThat(out).isInstanceOf(Decision.Reject.class);
        assertThat(((Decision.Reject) out).reason()).isEqualTo("ISIN IE00B3XXRP09 not in approved universe");

        var sent = ctx("10", Map.of(), "10", "10", Map.of(), Set.of(RiskContext.key(A, Side.BUY)), true);
        assertThat(Rules.duplicateOrder().evaluate(order(A, Side.BUY, "1", null), sent)).isInstanceOf(Decision.Reject.class);
        assertThat(Rules.duplicateOrder().evaluate(order(A, Side.SELL, "1", null), sent)).isInstanceOf(Decision.Approve.class);

        var closed = ctx("10", Map.of(), "10", "10", Map.of(), Set.of(), false);
        assertThat(Rules.marketHours().evaluate(order(A, Side.BUY, "1", null), closed)).isInstanceOf(Decision.Defer.class);
        assertThat(Rules.marketHours().evaluate(order(A, Side.BUY, "1", null), cash("10"))).isInstanceOf(Decision.Approve.class);
        assertThat(Rules.marketHours().name()).isEqualTo("market-hours");
    }

    // ---- account-wide concentration

    @Test
    void theAccountLimitFlagsAnInstrumentOnlyAboveTheCap() {
        var limits = new AccountLimits(bd("0.25"));
        var prices = Map.of(A, bd("10"));
        // two agents, 100 in total: A is exactly 25 -> allowed; 26 -> violation
        var exactly = Map.of("x", new PortfolioState(Map.of(A, bd("1.5")), bd("55")),
                "y", new PortfolioState(Map.of(A, bd("1")), bd("20")));
        assertThat(limits.check(exactly, prices)).isEmpty();
        var over = Map.of("x", new PortfolioState(Map.of(A, bd("1.6")), bd("54")),
                "y", new PortfolioState(Map.of(A, bd("1")), bd("20")));
        var violations = limits.check(over, prices);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).isin()).isEqualTo(A);
        assertThat(violations.get(0).weight()).isEqualByComparingTo("0.26");
        assertThat(violations.get(0).limit()).isEqualByComparingTo("0.25");
        assertThat(limits.check(Map.of(), prices)).isEmpty();
        assertThat(limits.check(Map.of("x", new PortfolioState(Map.of(), BigDecimal.ZERO)), prices)).isEmpty();
    }
}
