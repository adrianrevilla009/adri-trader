package com.adrianrevilla.trading.adapter.out.brokeretoro;

import static org.assertj.core.api.Assertions.assertThat;

import com.adrianrevilla.trading.core.application.RateLimiter;
import com.adrianrevilla.trading.core.application.port.out.InstrumentCatalog;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.application.port.out.BrokerRefStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real round trip against the eToro DEMO account: a 10 USD buy of EUNL.DE and its close. Skipped unless
 * ETORO_LIVE_DEMO=1 (and ETORO_API_KEY / ETORO_USER_KEY hold DEMO keys). The mode is fixed to DEMO here and the cap
 * per order is 10, so this test can never touch the real account or spend more than 10. Run it with:
 * {@code ETORO_LIVE_DEMO=1 mvn -pl trading-broker-etoro -am test -Dtest=EToroDemoLiveTest}.
 */
@EnabledIfEnvironmentVariable(named = "ETORO_LIVE_DEMO", matches = "1")
class EToroDemoLiveTest {
    private static final Isin EUNL = new Isin("IE00B4L5Y983");
    private static final long EUNL_ID = 15348L;
    private static final BigDecimal CAP = BigDecimal.TEN;

    private final Map<UUID, String> refStore = new HashMap<>();

    private EToroBroker broker() {
        var cfg = new EToroConfig("https://public-api.etoro.com", System.getenv("ETORO_API_KEY"),
                System.getenv("ETORO_USER_KEY"), EToroConfig.Mode.DEMO, Duration.ofSeconds(15),
                EToroConfig.Endpoints.defaults(), CAP);
        InstrumentCatalog catalog = new InstrumentCatalog() {
            public Optional<Long> brokerId(Isin isin) { return isin.equals(EUNL) ? Optional.of(EUNL_ID) : Optional.empty(); }
            public Optional<Isin> isin(long id) { return id == EUNL_ID ? Optional.of(EUNL) : Optional.empty(); }
        };
        BrokerRefStore refs = new BrokerRefStore() {
            public void put(UUID id, String ref) { refStore.put(id, ref); }
            public Optional<String> get(UUID id) { return Optional.ofNullable(refStore.get(id)); }
        };
        return new EToroBroker(cfg, catalog, refs, RateLimiter.perMinute(30), RateLimiter.perMinute(10), Thread::sleep);
    }

    private static <T> T eventually(Supplier<T> action, java.util.function.Predicate<T> done) throws Exception {
        T last = null;
        for (int i = 0; i < 20; i++) {
            last = action.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(1000);
        }
        return last;
    }

    @Test
    void buyTenAndCloseItInDemo() throws Exception {
        EToroBroker b = broker();
        BigDecimal held = b.positions().getOrDefault(EUNL, BigDecimal.ZERO);
        var buy = new OrderRequest(UUID.randomUUID(), "live-test", EUNL, OrderRequest.Side.BUY, CAP, null);
        b.submit(buy);
        try {
            BrokerOrder bought = eventually(() -> lookup(b, buy), o -> o.state() == OrderState.FILLED);
            assertThat(bought.state()).isEqualTo(OrderState.FILLED);
            assertThat(bought.filledQuantity().signum()).isPositive();
            // the portfolio lags a moment behind the order (eventual consistency, seen on 2026-11-26)
            BigDecimal after = eventually(() -> positionsOf(b), p -> p.compareTo(held) > 0);
            assertThat(after).isGreaterThan(held);
        } finally {
            if (positionsOf(b).signum() > 0) {   // only clean up what is really open, never hide the first failure
                var sell = new OrderRequest(UUID.randomUUID(), "live-test", EUNL, OrderRequest.Side.SELL, CAP, null, true);
                b.submit(sell);
                BrokerOrder closed = eventually(() -> lookup(b, sell), o -> o.state() == OrderState.FILLED);
                assertThat(closed.state()).isEqualTo(OrderState.FILLED);
            }
        }
        assertThat(eventually(() -> positionsOf(b), p -> p.signum() == 0)).isEqualByComparingTo("0");
    }

    private static BrokerOrder lookup(EToroBroker b, OrderRequest o) {
        try {
            return b.lookup(o.internalId()).orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal positionsOf(EToroBroker b) {
        try {
            return b.positions().getOrDefault(EUNL, BigDecimal.ZERO);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
