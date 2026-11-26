package com.adrianrevilla.trading.adapter.out.brokeretoro;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * @param baseUrl host, for example https://public-api.etoro.com
 * @param maxOrderAmount hard cap on the amount of one order; a bigger order is rejected before anything is sent
 * @param mode DEMO uses the demo endpoints; REAL uses the real ones and runs the preflight check first
 */
public record EToroConfig(String baseUrl, String apiKey, String userKey, Mode mode, Duration timeout,
        Endpoints endpoints, BigDecimal maxOrderAmount) {

    public EToroConfig {
        if (maxOrderAmount == null || maxOrderAmount.signum() <= 0) {
            throw new IllegalArgumentException("maxOrderAmount must be positive: it is the hard cap per order");
        }
    }

    public enum Mode { DEMO, REAL }

    /**
     * API paths. The demo ones were verified on 2026-11-26 with a demo key (one buy and one close, 10 USD). The real
     * ones come from the eToro documentation and are NOT VERIFIED until the first real order.
     */
    public record Endpoints(String ordersDemo, String ordersReal, String lookupDemo, String lookupReal,
            String closeDemo, String closeReal, String closeStatusDemo, String closeStatusReal,
            String estimate, String eligibility, String portfolio) {

        public static Endpoints defaults() {
            return new Endpoints(
                    "/api/v2/trading/execution/demo/orders",                                   // verified 2026-11-26
                    "/api/v2/trading/execution/orders",                                        // docs only
                    "/api/v2/trading/info/demo/orders:lookup",                                 // verified 2026-11-26
                    "/api/v2/trading/info/orders:lookup",                                      // docs only
                    "/api/v1/trading/execution/demo/market-close-orders/positions/{positionId}", // verified 2026-11-26
                    "/api/v1/trading/execution/market-close-orders/positions/{positionId}",    // docs only
                    "/api/v1/trading/info/demo/close-orders/{id}",                             // verified 2026-11-26
                    "/api/v1/trading/info/real/close-orders/{id}",                             // docs only
                    "/api/v2/trading/info/costs",              // verified 2026-11-24 (what-if cost, query only)
                    "/api/v2/trading/info/eligibility",        // verified 2026-11-24 (real key); demo needs /info/demo/
                    "/api/v1/trading/info/{env}/pnl");         // verified 2026-11-23 and 2026-11-26 (env = demo|real)
        }
    }
}
