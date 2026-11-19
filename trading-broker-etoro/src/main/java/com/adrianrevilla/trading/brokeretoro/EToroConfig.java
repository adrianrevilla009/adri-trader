package com.adrianrevilla.trading.brokeretoro;

import java.time.Duration;

/**
 * @param baseUrl host, for example https://public-api.etoro.com
 * @param mode DEMO uses the demo order endpoint; REAL uses the real one and runs the preflight check first
 */
public record EToroConfig(String baseUrl, String apiKey, String userKey, Mode mode, Duration timeout,
        Endpoints endpoints) {

    public enum Mode { DEMO, REAL }

    /**
     * API paths. The two order endpoints come from the project brief (eToro public API portal). The others are
     * NOT VERIFIED: they are placeholders to be confirmed against the real API with the demo key in phase 0.
     */
    public record Endpoints(String ordersDemo, String ordersReal, String orderStatus, String estimate,
            String portfolio) {

        public static Endpoints unverifiedDefaults() {
            return new Endpoints(
                    "/api/v2/trading/execution/demo/orders",
                    "/api/v2/trading/execution/orders",
                    "/api/v2/trading/execution/orders/{id}",   // unverified
                    "/api/v2/trading/execution/estimate",      // unverified (what-if cost endpoint)
                    "/api/v1/trading/portfolio");              // unverified
        }
    }
}
