package com.adrianrevilla.trading.execution.adapter.out.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderRequest.Side;
import com.adrianrevilla.trading.core.domain.OrderState;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryOrderRepositoryTest {
    private static final Isin A = new Isin("IE00B4L5Y983");
    private static final Isin B = new Isin("IE00B5BMR087");

    private InMemoryOrderRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryOrderRepository();
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
    void emptyRepositoryHasNothingCommittedAndNoUnits() {
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

        // half filled: 5 invested + 5 still open = 10, not 15
        fill(o, "0.5", "10");
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
