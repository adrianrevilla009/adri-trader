package com.adrianrevilla.trading.execution;

import com.adrianrevilla.trading.core.Alerter;
import com.adrianrevilla.trading.core.Broker;
import com.adrianrevilla.trading.core.BrokerAccessDeniedException;
import com.adrianrevilla.trading.core.BrokerException;
import com.adrianrevilla.trading.core.BrokerOrder;
import com.adrianrevilla.trading.core.BrokerRejectedException;
import com.adrianrevilla.trading.core.BrokerUnavailableException;
import com.adrianrevilla.trading.core.KillSwitch;
import com.adrianrevilla.trading.core.OrderRequest;
import com.adrianrevilla.trading.core.OrderState;
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
    public int syncOpenOrders() {
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
            if (apply(rec, found.get())) {
                changed++;
            }
        }
        return changed;
    }

    private boolean apply(OrderRepository.OrderRecord rec, BrokerOrder b) {
        boolean changed = false;
        BigDecimal recorded = orders.recordedFillAmount(rec.request().internalId());
        BigDecimal delta = b.filledAmountEur().subtract(recorded);
        if (delta.signum() > 0) {
            BigDecimal qtyDelta = b.averagePrice() != null && b.averagePrice().signum() > 0
                    ? delta.divide(b.averagePrice(), java.math.MathContext.DECIMAL128) : BigDecimal.ZERO;
            orders.recordFill(rec.request().internalId(), qtyDelta, b.averagePrice(), delta);
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

    /** Compares the ledger of the agent with the broker; a difference stops all trading. */
    public Reconciler.Report reconcile(String agentId) {
        try {
            Reconciler.Report report = reconciler.compare(orders.netUnits(agentId), broker.positions());
            if (!report.ok()) {
                halt("Reconciliation difference for " + agentId + ": " + report.differences());
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
