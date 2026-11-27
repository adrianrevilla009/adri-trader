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

class CashCappedBrokerTest {
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

    @Test
    void cashIsCappedWhenTheAccountHoldsMore() throws Exception {
        assertThat(new CashCappedBroker(new Fake("100000"), new BigDecimal("40")).cash()).isEqualByComparingTo("40");
    }

    @Test
    void cashIsNotRaisedWhenTheAccountHoldsLess() throws Exception {
        assertThat(new CashCappedBroker(new Fake("12.5"), new BigDecimal("40")).cash()).isEqualByComparingTo("12.5");
    }

    @Test
    void ordersAndPositionsPassThroughUntouched() throws Exception {
        var fake = new Fake("100");
        var broker = new CashCappedBroker(fake, new BigDecimal("40"));
        var order = new OrderRequest(UUID.randomUUID(), "a", ISIN, OrderRequest.Side.BUY, BigDecimal.TEN, null);
        broker.submit(order);
        assertThat(fake.submitted).containsExactly(order);
        assertThat(broker.positions().get(ISIN)).isEqualByComparingTo("2");
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> new CashCappedBroker(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CashCappedBroker(new Fake("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CashCappedBroker(new Fake("1"), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
