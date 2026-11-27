package com.adrianrevilla.trading.execution.application;

import com.adrianrevilla.trading.core.application.port.out.Alerter;
import com.adrianrevilla.trading.core.domain.Broker;
import com.adrianrevilla.trading.core.domain.BrokerAccessDeniedException;
import com.adrianrevilla.trading.core.domain.BrokerException;
import com.adrianrevilla.trading.core.domain.BrokerOrder;
import com.adrianrevilla.trading.core.domain.BrokerRejectedException;
import com.adrianrevilla.trading.core.domain.BrokerUnavailableException;
import com.adrianrevilla.trading.core.domain.KillSwitch;
import com.adrianrevilla.trading.core.domain.OrderRequest;
import com.adrianrevilla.trading.core.domain.OrderState;
import com.adrianrevilla.trading.execution.application.port.out.OrderRepository;
import com.adrianrevilla.trading.execution.domain.OrderStateMachine;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Sends orders without duplicating or losing any:
 * <ol>
 *   <li>the order is stored (CREATED) and marked SUBMITTED before the broker is called;</li>
 *   <li>a definitive refusal marks it REJECTED; an unknown outcome leaves it SUBMITTED with an UNKNOWN note;</li>
 *   <li>an order is never sent twice: the internal id is unique in the store and nothing is resent blindly;</li>
 *   <li>anything that cannot be explained (an order the broker does not know, a position that does not match)
 *       activates the kill switch and alerts, and nothing is fixed automatically.</li>
 * </ol>
 */
public final class ExecutionService {
    public enum SendOutcome { SUBMITTED, REJECTED, UNKNOWN, BLOCKED }

    static final String UNKNOWN_NOTE = "UNKNOWN: outcome of submit not known";

    private final Broker broker;
    private final OrderRepository orders;
    private final KillSwitch killSwitch;
    private final Alerter alerter;
    private final Reconciler reconciler;

    public ExecutionService(Broker broker, OrderRepository orders, KillSwitch killSwitch, Alerter alerter,
            Reconciler reconciler) {
        this.broker = broker;
        this.orders = orders;
        this.killSwitch = killSwitch;
        this.alerter = alerter;
        this.reconciler = reconciler;
    }

    public SendOutcome send(OrderRequest request) {
        if (killSwitch.isActive()) {
            return SendOutcome.BLOCKED;
        }
        orders.create(request); // throws DuplicateOrderException: the same order is never sent twice
        orders.updateState(request.internalId(), OrderState.SUBMITTED, "sending");
        try {
            broker.submit(request);
            return SendOutcome.SUBMITTED;
        } catch (BrokerAccessDeniedException e) {
            orders.updateState(request.internalId(), OrderState.REJECTED, e.getMessage());
            halt("Broker denied access: " + e.getMessage());
            return SendOutcome.REJECTED;
        } catch (BrokerRejectedException e) {
            orders.updateState(request.internalId(), OrderState.REJECTED, e.getMessage());
            return SendOutcome.REJECTED;
        } catch (BrokerUnavailableException e) {
            orders.updateState(request.internalId(), OrderState.SUBMITTED, UNKNOWN_NOTE);
            alerter.alert(Alerter.Level.WARNING, "Order " + request.internalId() + " outcome unknown: " + e.getMessage());
            return SendOutcome.UNKNOWN;
        } catch (BrokerException e) {
            orders.updateState(request.internalId(), OrderState.SUBMITTED, UNKNOWN_NOTE);
            return SendOutcome.UNKNOWN;
        }
    }

    /** Asks the broker for every open order and brings the store up to date. Returns how many changed. */
    public synchronized int syncOpenOrders() {
        int changed = 0;
        for (OrderRepository.OrderRecord rec : orders.findOpen()) {
            Optional<BrokerOrder> found;
            try {
                found = broker.lookup(rec.request().internalId());
            } catch (BrokerException e) {
                continue; // try again on the next pass; never guess
            }
            if (found.isEmpty()) {
                halt("Order " + rec.request().internalId() + " is unknown to the broker; not resending, manual check");
                continue;
            }
            if (apply(rec, found.orElseThrow())) {
                changed++;
            }
        }
        return changed;
    }

    private boolean apply(OrderRepository.OrderRecord rec, BrokerOrder b) {
        boolean changed = false;
        if (orders.recordFillUpTo(rec.request().internalId(), b.filledAmountEur(), b.averagePrice())) {
            changed = true;
        }
        if (b.state() != rec.state() && OrderStateMachine.canTransition(rec.state(), b.state())) {
            orders.updateState(rec.request().internalId(), b.state(), "synced");
            changed = true;
        } else if (!OrderStateMachine.canTransition(rec.state(), b.state())) {
            halt("Order " + rec.request().internalId() + " illegal transition " + rec.state() + " -> " + b.state());
        }
        return changed;
    }

    /** Compares the ledger of one agent with the broker; a difference stops all trading. Only right for a single agent. */
    public Reconciler.Report reconcile(String agentId) {
        return compare(orders.netUnits(agentId), "agent " + agentId);
    }

    /**
     * Compares the ledger of EVERY agent, added up, with the broker. Several agents share one account, so the account
     * holds the sum of their positions and no single agent can explain it alone. A difference stops all trading.
     */
    public Reconciler.Report reconcileAll() {
        return compare(orders.netUnitsAll(), "all agents");
    }

    private Reconciler.Report compare(java.util.Map<com.adrianrevilla.trading.core.domain.Isin, BigDecimal> ledger,
            String who) {
        try {
            Reconciler.Report report = reconciler.compare(ledger, broker.positions());
            if (!report.ok()) {
                halt("Reconciliation difference for " + who + ": " + report.differences());
            }
            return report;
        } catch (BrokerException e) {
            alerter.alert(Alerter.Level.WARNING, "Reconciliation not possible: " + e.getMessage());
            return new Reconciler.Report(java.util.List.of(new Reconciler.Difference("broker unreachable",
                    BigDecimal.ZERO, BigDecimal.ZERO)));
        }
    }

    private void halt(String reason) {
        killSwitch.activate(reason);
        alerter.alert(Alerter.Level.CRITICAL, "TRADING HALTED: " + reason);
    }
}
