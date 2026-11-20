package com.adrianrevilla.trading.strategies.domain;

import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.MarketSnapshot;
import com.adrianrevilla.trading.core.domain.PortfolioState;
import com.adrianrevilla.trading.core.domain.Strategy;
import com.adrianrevilla.trading.core.domain.TargetPortfolio;
import java.math.BigDecimal;
import java.util.Map;

/** Benchmark / control: holds one instrument fully invested. */
public final class BuyAndHold implements Strategy {
    private final String id;
    private final Isin isin;

    public BuyAndHold(String id, Isin isin) {
        this.id = id;
        this.isin = isin;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TargetPortfolio decide(MarketSnapshot snapshot, PortfolioState state) {
        return snapshot.latest(isin).isPresent()
                ? new TargetPortfolio(Map.of(isin, BigDecimal.ONE))
                : TargetPortfolio.allCash();
    }
}
