package com.adrianrevilla.trading.core.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wraps a broker and reports at most {@code capital} as cash, so an agent sizes its orders on its own small capital
 * and not on the whole account (a demo account holds 100000, the project trades 100). Everything else is passed on.
 */
public final class CashCappedBroker implements Broker {
    private final Broker delegate;
    private final BigDecimal capital;

    public CashCappedBroker(Broker delegate, BigDecimal capital) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate is required");
        }
        if (capital == null || capital.signum() <= 0) {
            throw new IllegalArgumentException("capital must be positive");
        }
        this.delegate = delegate;
        this.capital = capital;
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
        return delegate.cash().min(capital);
    }
}
