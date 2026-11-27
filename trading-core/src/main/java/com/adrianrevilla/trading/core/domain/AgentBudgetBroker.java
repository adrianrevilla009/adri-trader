package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Gives one agent its own budget on a shared account (ADR-0030). The cash it sees is its capital minus what it has
 * already committed (net money invested plus open buy orders), never negative and never more than the account really
 * holds. Unlike {@link CashCappedBroker}, spending reduces the budget, so several agents can share one account without
 * any of them sizing orders on money that belongs to another. Java: {@code Supplier} is the same interface.
 */
public final class AgentBudgetBroker implements Broker {
    private final Broker delegate;
    private final BigDecimal capital;
    private final Supplier<BigDecimal> committed;

    /** @param committed read on every call; net invested (buys minus sells) plus buy orders not yet filled */
    public AgentBudgetBroker(Broker delegate, BigDecimal capital, Supplier<BigDecimal> committed) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        if (capital == null || capital.signum() <= 0) {
            throw new IllegalArgumentException("capital must be positive");
        }
        if (committed == null) {
            throw new IllegalArgumentException("committed is required");
        }
        this.delegate = delegate;
        this.capital = capital;
        this.committed = committed;
    }

    @Override
    public void submit(OrderRequest request) throws BrokerException {
        delegate.submit(request);
    }

    @Override
    public void cancel(UUID internalId) throws BrokerException {
        delegate.cancel(internalId);
    }

    @Override
    public Optional<BrokerOrder> lookup(UUID internalId) throws BrokerException {
        return delegate.lookup(internalId);
    }

    @Override
    public Map<Isin, BigDecimal> positions() throws BrokerException {
        return delegate.positions();
    }

    @Override
    public BigDecimal cash() throws BrokerException {
        BigDecimal budget = capital.subtract(committed.get()).max(BigDecimal.ZERO);
        return delegate.cash().min(budget);
    }
}
