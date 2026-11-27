package com.adrianrevilla.trading.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AgentBudgetBrokerTest {
    private static final Isin ISIN = new Isin("IE00B4L5Y983");

    private static final class Fake implements Broker {
        final BigDecimal cash;
        final List<OrderRequest> submitted = new ArrayList<>();

        Fake(String cash) {
            this.cash = new BigDecimal(cash);
        }

        public void submit(OrderRequest r) { submitted.add(r); }
        public void cancel(UUID id) {}
        public Optional<BrokerOrder> lookup(UUID id) { return Optional.empty(); }
        public Map<Isin, BigDecimal> positions() { return Map.of(ISIN, new BigDecimal("2")); }
        public BigDecimal cash() { return cash; }
    }

    /** capital, committed (net invested + open buys), account cash -> expected cash seen by the agent */
    @ParameterizedTest
    @CsvSource({
            "40, 0, 100000, 40",      // nothing invested yet: the whole capital
            "40, 10, 100000, 30",     // 10 already invested
            "40, 40, 100000, 0",      // fully invested
            "40, 55, 100000, 0",      // never negative, even if the position grew in cost
            "40, -5, 100000, 45",     // sold above cost: the gain can be reused
            "40, 0, 12.5, 12.5",      // never more than the account really holds
            "40, 10, 12.5, 12.5"})
    void cashIsCapitalMinusCommittedAndNeverMoreThanTheAccount(String capital, String committed, String account,
            String expected) throws Exception {
        var broker = new AgentBudgetBroker(new Fake(account), new BigDecimal(capital),
                () -> new BigDecimal(committed));
        assertThat(broker.cash()).isEqualByComparingTo(expected);
    }

    @Test
    void readsTheCommittedAmountEachTimeSoItFollowsTheLedger() throws Exception {
        BigDecimal[] committed = {BigDecimal.ZERO};
        var broker = new AgentBudgetBroker(new Fake("1000"), new BigDecimal("40"), () -> committed[0]);
        assertThat(broker.cash()).isEqualByComparingTo("40");
        committed[0] = new BigDecimal("10");
        assertThat(broker.cash()).isEqualByComparingTo("30");
    }

    @Test
    void everythingElseIsPassedOn() throws Exception {
        var fake = new Fake("100");
        var broker = new AgentBudgetBroker(fake, new BigDecimal("40"), () -> BigDecimal.ZERO);
        var order = new OrderRequest(UUID.randomUUID(), "a", ISIN, OrderRequest.Side.BUY, new BigDecimal("10"), null);
        broker.submit(order);
        assertThat(fake.submitted).containsExactly(order);
        assertThat(broker.positions()).containsEntry(ISIN, new BigDecimal("2"));
    }

    @Test
    void rejectsBadConstruction() {
        var fake = new Fake("100");
        assertThatThrownBy(() -> new AgentBudgetBroker(null, BigDecimal.TEN, () -> BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudgetBroker(fake, BigDecimal.ZERO, () -> BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudgetBroker(fake, null, () -> BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentBudgetBroker(fake, BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
