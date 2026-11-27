package com.adrianrevilla.trading.app.config;

import com.adrianrevilla.trading.adapter.out.brokeretoro.EToroConfig;
import com.adrianrevilla.trading.core.domain.Isin;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The eToro connection (prefix {@code trading.etoro}). Keys only come from the environment. Only DEMO is accepted:
 * real orders need ADR-0008 and the per-agent real gate, which this version does not open.
 *
 * @param instruments entries like {@code IE00B4L5Y983=15348} (ISIN = eToro instrument id)
 * @param maxOrderAmount hard cap on one order; a bigger one is rejected before anything is sent
 * @param minOrderAmount smallest order eToro accepts (10 USD, verified 2026-11-27: it accepts a smaller request and
 *     rejects it afterwards with error 720); the risk manager drops smaller orders
 */
@ConfigurationProperties("trading.etoro")
public record EtoroProperties(
        @DefaultValue("https://public-api.etoro.com") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String userKey,
        @DefaultValue("DEMO") EToroConfig.Mode mode,
        @DefaultValue("60") int maxReadsPerMinute,
        @DefaultValue("20") int maxWritesPerMinute,
        @DefaultValue("10") BigDecimal maxOrderAmount,
        @DefaultValue("10") BigDecimal minOrderAmount,
        @DefaultValue("") List<String> instruments) {

    public EtoroProperties {
        if (mode != EToroConfig.Mode.DEMO) {
            throw new IllegalArgumentException(
                    "trading.etoro.mode must be DEMO: real orders are not enabled (ADR-0008 and the real gate)");
        }
        if (maxReadsPerMinute <= 0 || maxWritesPerMinute <= 0) {
            throw new IllegalArgumentException("trading.etoro rate limits must be positive");
        }
        if (maxOrderAmount.signum() <= 0) {
            throw new IllegalArgumentException("trading.etoro.max-order-amount must be positive");
        }
        if (minOrderAmount.signum() <= 0 || minOrderAmount.compareTo(maxOrderAmount) > 0) {
            throw new IllegalArgumentException("trading.etoro.min-order-amount must be positive and not above the cap");
        }
        instruments = instruments.stream().filter(e -> !e.isBlank()).toList();
        instrumentIds(instruments); // fails the start on a malformed entry
    }

    public boolean hasKeys() {
        return !apiKey.isBlank() && !userKey.isBlank();
    }

    public Map<Isin, Long> instrumentIds() {
        return instrumentIds(instruments);
    }

    private static Map<Isin, Long> instrumentIds(List<String> entries) {
        Map<Isin, Long> out = new HashMap<>();
        for (String e : entries) {
            String[] kv = e.split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("trading.etoro.instruments entry must be ISIN=id, was '" + e + "'");
            }
            try {
                out.put(new Isin(kv[0].trim()), Long.parseLong(kv[1].trim()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("trading.etoro.instruments entry is invalid: '" + e + "'", ex);
            }
        }
        return Map.copyOf(out);
    }
}
