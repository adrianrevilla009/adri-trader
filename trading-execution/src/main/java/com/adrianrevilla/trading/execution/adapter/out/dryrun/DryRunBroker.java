package com.adrianrevilla.trading.execution.adapter.out.dryrun;

import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerException;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.Isin;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Dry-run: computes and records the orders but never sends them. Reads go to the real broker. */
public final class DryRunBroker implements Broker {
    private final Broker delegate;
    private final List<OrderRequest> recorded = Collections.synchronizedList(new ArrayList<>());

    public DryRunBroker(Broker delegate) {
        this.delegate = delegate;
    }

    public List<OrderRequest> recorded() {
        return List.copyOf(recorded);
    }

    @Override
    public void submit(OrderRequest request) {
        recorded.add(request);
    }

    @Override
    public void cancel(UUID internalId) {
        // nothing was sent
    }

    @Override
    public Optional<BrokerOrder> lookup(UUID internalId) {
        return Optional.empty();
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
