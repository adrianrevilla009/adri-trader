package com.adrianrevilla.trading.backtest;

import com.adrianrevilla.trading.core.Isin;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Execution costs, all configurable (ADR-0004). Commission is 0 on eToro real ETFs, so the cost is half the
 * spread (per instrument, in basis points) plus an extra slippage between decision price and execution price.
 */
public record CostModel(BigDecimal defaultSpreadBps, Map<Isin, BigDecimal> spreadBpsByInstrument, BigDecimal slippageBps)
        implements com.adrianrevilla.trading.core.ExecutionCosts {

    public CostModel {
        spreadBpsByInstrument = Map.copyOf(spreadBpsByInstrument);
    }

    public static CostModel of(String spreadBps, String slippageBps) {
        return new CostModel(new BigDecimal(spreadBps), Map.of(), new BigDecimal(slippageBps));
    }

    public static CostModel none() {
        return of("0", "0");
    }

    @Override
    public BigDecimal adverseFraction(Isin isin) {
        BigDecimal spread = spreadBpsByInstrument.getOrDefault(isin, defaultSpreadBps);
        return spread.divide(BigDecimal.valueOf(2)).add(slippageBps).divide(BigDecimal.valueOf(10_000));
    }
}
