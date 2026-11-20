package com.adrianrevilla.trading.brokersim.domain;

import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerException;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.BrokerUnavailableException;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fake broker for failure tests: wraps another broker and fails on script. It simulates definitive refusals,
 * connection cut before the order arrives, response lost after it arrived, and failing lookups.
 */
public final class FaultInjectingBroker implements Broker {
    public enum Fault {
        /** The broker refuses the order for good. */
        REJECT,
        /** Connection fails before the order reaches the broker. */
        TIMEOUT_BEFORE_DELIVERY,
        /** The order reaches the broker but the response is lost. */
        TIMEOUT_AFTER_DELIVERY
    }

    private final Broker delegate;
    private final Deque<Fault> submitFaults = new ArrayDeque<>();
    private int lookupFailures;

    public FaultInjectingBroker(Broker delegate) {
        this.delegate = delegate;
    }

    public synchronized void failNextSubmit(Fault fault) {
        submitFaults.addLast(fault);
    }

    public synchronized void failNextLookups(int count) {
        lookupFailures = count;
    }

    @Override
    public synchronized void submit(OrderRequest request) throws BrokerException {
        Fault f = submitFaults.pollFirst();
        if (f == null) {
            delegate.submit(request);
            return;
        }
        switch (f) {
            case REJECT -> throw new BrokerRejectedException("injected rejection");
            case TIMEOUT_BEFORE_DELIVERY -> throw new BrokerUnavailableException("injected timeout before delivery");
            case TIMEOUT_AFTER_DELIVERY -> {
                delegate.submit(request);
                throw new BrokerUnavailableException("injected timeout after delivery");
            }
        }
    }

    @Override
    public void cancel(UUID internalId) throws BrokerException {
        delegate.cancel(internalId);
    }

    @Override
    public synchronized Optional<BrokerOrder> lookup(UUID internalId) throws BrokerException {
        if (lookupFailures > 0) {
            lookupFailures--;
            throw new BrokerUnavailableException("injected lookup failure");
        }
        return delegate.lookup(internalId);
    }

    /** Test helper: whether the wrapped broker knows the order, ignoring scripted faults. */
    public boolean lookupQuietly(UUID internalId) {
        try {
            return delegate.lookup(internalId).isPresent();
        } catch (BrokerException e) {
            return false;
        }
    }

    @Override
    public Map<Isin, BigDecimal> positions() throws BrokerException {
        return delegate.positions();
    }

    @Override
    public BigDecimal cash() throws BrokerException {
        return delegate.cash();
    }
}
